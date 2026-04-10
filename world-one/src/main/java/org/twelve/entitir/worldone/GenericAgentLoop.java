package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.twelve.entitir.aip.LLMCaller;
import org.twelve.entitir.aip.worldone.ChatEvent;
import org.twelve.entitir.ontology.llm.LLMConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * 通用 AI Agent 循环 — World One 的核心，不含任何领域知识。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>持有对话历史</li>
 *   <li>从 {@link AppRegistry} 读取工具定义（跨所有已注册 app）</li>
 *   <li>通过 LLM 决策调用哪个工具</li>
 *   <li>将工具调用 HTTP 路由到正确的 app（POST app/api/tools/{name}）</li>
 *   <li>透传工具结果中的 canvas 字段为 ChatEvent#canvas</li>
 * </ul>
 *
 * <h2>inject_context 协议（AIPP 扩展）</h2>
 * <p>worldone 对所有工具调用始终注入 {@code _context.userId/sessionId/agentId}。
 * 对声明了 {@code inject_context.turn_messages=true} 的 skill，
 * 还额外注入本轮完整消息列表 {@code turn_messages}（如 memory_consolidate）。
 */
public final class GenericAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(GenericAgentLoop.class);
    private static final int MAX_TOOL_ROUNDS = 10;
    /** LLM 上下文窗口大小。 */
    private static final int CONTEXT_WINDOW  = 40;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String      sessionId;
    private final String      userId;
    private final LLMCaller   llm;
    private final AppRegistry registry;
    /** 完整对话历史（第 0 条永远是 system prompt）。 */
    private final List<Map<String, Object>> history = new ArrayList<>();

    private String activeWidgetType   = null;
    private String sessionEntryPrompt = null;
    /** workspaceId = canvas world ID；canvas 模式下由 WorldOneChatController 注入。 */
    private String workspaceId        = null;
    /** human-readable workspace title，用于注入 consolidation prompt。 */
    private String workspaceTitle     = null;
    /** 本轮临时 UI 上下文提示（最高优先级，不进入 history，仅作用于当前 chat() 调用）。 */
    private List<String> currentTurnHints = List.of();

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public GenericAgentLoop(String sessionId, LLMConfig config, AppRegistry registry) {
        this(sessionId, "default", config, registry);
    }

    public GenericAgentLoop(String sessionId, String userId, LLMConfig config, AppRegistry registry) {
        this.sessionId = sessionId;
        this.userId    = userId;
        this.llm       = new LLMCaller(config);
        this.registry  = registry;
        history.add(Map.of("role", "system", "content", registry.aggregatedSystemPrompt()));
    }

    /** 从持久化存储恢复历史消息（重启后重建对话上下文时调用）。 */
    public void restoreHistory(List<Map<String, Object>> messages) {
        history.addAll(messages);
    }

    /** 恢复 canvas 模式（服务重启后由 WorldOneChatController 调用）。 */
    public void setActiveWidgetType(String widgetType) {
        this.activeWidgetType = widgetType;
    }

    /** 设置当前 canvas world 的 workspaceId（= worldId），注入到所有 _context。 */
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    /** 设置当前 workspace 的可读名称，用于 consolidation prompt 注入。 */
    public void setWorkspaceTitle(String workspaceTitle) {
        this.workspaceTitle = workspaceTitle;
    }

    /** 设置 session 入场提示词（Layer 2）。 */
    public void setSessionEntryPrompt(String prompt) {
        this.sessionEntryPrompt = prompt;
    }

    /** 返回当前 session 入场提示词（用于重启恢复判断）。 */
    public String getSessionEntryPrompt() {
        return sessionEntryPrompt;
    }

    /**
     * 处理一条用户消息，通过 emit 回调实时推送 ChatEvent。
     */
    public void chat(String userMessage, Consumer<ChatEvent> emit) {
        chat(userMessage, List.of(), emit);
    }

    /**
     * 从 Apps 面板直接打开 app 的主 widget，绕过 LLM。
     *
     * <p>找到 appId 的 main widget，调用其 renders_output_of_skill，
     * 将结果作为 ChatEvent 推送（canvas / html_widget / session）。
     */
    public void openApp(String appId, Consumer<ChatEvent> emit) {
        openApp(appId, Map.of(), emit);
    }

    /**
     * 从 Apps 面板直接打开指定 app 的 main widget（可携带额外工具参数）。
     *
     * <p>当 {@code extraArgs} 非空时，直接调用指定 skill（{@code tool_name} 键）
     * 或 main widget 的 {@code renders_output_of_skill}，并将 {@code extraArgs}
     * 作为 args 传入。这使得 "点击世界卡片进入世界" 与 LLM 调用 world_design
     * 走完全相同的代码路径。
     *
     * @param extraArgs 可选额外参数，如 {@code {name: "My World"}} 或
     *                  {@code {session_id: "xxx"}}；空 Map 表示走默认 main widget 入口。
     */
    public void openApp(String appId, Map<String, Object> extraArgs, Consumer<ChatEvent> emit) {
        // 如果 extraArgs 指定了 tool_name，直接调用该 skill；否则走 main widget 入口
        String skillName;
        if (extraArgs.containsKey("_tool")) {
            skillName = (String) extraArgs.get("_tool");
        } else {
            String mainWidgetType = registry.getAppMainWidgetType(appId);
            if (mainWidgetType == null) {
                emit.accept(ChatEvent.error("App '" + appId + "' has no main widget registered"));
                emit.accept(ChatEvent.done());
                return;
            }
            skillName = registry.findOutputSkillForWidget(mainWidgetType);
            if (skillName == null) {
                emit.accept(ChatEvent.error("Main widget '" + mainWidgetType + "' has no renders_output_of_skill"));
                emit.accept(ChatEvent.done());
                return;
            }
        }
        try {
            AppRegistration app = registry.findAppForTool(skillName);
            String url = app.toolUrl(skillName);
            // args = extraArgs（去掉内部 _tool 键）
            Map<String, Object> args = new LinkedHashMap<>(extraArgs);
            args.remove("_tool");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("args", args);
            body.put("_context", Map.of(
                "userId",      userId,
                "sessionId",   sessionId,
                "workspaceId", workspaceId   != null ? workspaceId   : "",
                "agentId",     "worldone"
            ));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            extractEvents(resp.body(), skillName, emit);
            log.debug("[OpenApp] Opened app {} via skill {}", appId, skillName);
        } catch (Exception e) {
            emit.accept(ChatEvent.error("Failed to open app: " + e.getMessage()));
        }
        emit.accept(ChatEvent.done());
    }

    /**
     * 处理一条用户消息，注入本轮 UI 上下文提示（最高优先级，不进入 history）。
     *
     * @param uiHints  前端当前 UI 状态（如"用户正在查看关系图谱"），注入 system prompt 首部
     */
    public void chat(String userMessage, List<String> uiHints, Consumer<ChatEvent> emit) {
        this.currentTurnHints = uiHints != null ? uiHints : List.of();
        history.add(Map.of("role", "user", "content", userMessage));

        List<Map<String, Object>> tools = registry.allSkillsAsTools();
        int turnStart = history.size() - 1;
        // Track every tool called this turn, for auto-refresh detection
        List<String> toolsCalledThisTurn = new ArrayList<>();

        try {
            int rounds = 0;
            boolean toolCalledAtLeastOnce = false;
            while (rounds++ < MAX_TOOL_ROUNDS) {
                String effectiveToolsJson = mergeCanvasTools(tools);
                String toolChoice = toolCalledAtLeastOnce ? "auto" : "required";

                // Always stream tokens so markdown renders incrementally from first token
                Consumer<String> textCallback     = token   -> emit.accept(ChatEvent.textToken(token));
                Consumer<String> thinkingCallback = thinking -> emit.accept(ChatEvent.thinking(thinking));

                LLMCaller.LLMResponse resp = llm.callStream(
                        contextWindow(), effectiveToolsJson,
                        LLMCaller.DEFAULT_MAX_TOKENS_TOOLS, toolChoice,
                        textCallback, thinkingCallback);

                // ── 工具调用 ──────────────────────────────────────────────
                if ("tool_calls".equals(resp.finishReason()) && !resp.toolCalls().isEmpty()) {
                    history.add(resp.rawAssistantMessage());
                    toolCalledAtLeastOnce = true;

                    // Snapshot current turn for potential turn_messages injection
                    List<Map<String, Object>> turnSnapshot =
                            new ArrayList<>(history.subList(turnStart, history.size()));

                    for (LLMCaller.ToolCall tc : resp.toolCalls()) {
                        emit.accept(ChatEvent.toolCall(tc.name()));
                        toolsCalledThisTurn.add(tc.name());

                        String toolResult = callToolViaHttp(tc, turnSnapshot);

                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role",         "tool");
                        toolMsg.put("tool_call_id", tc.id());
                        toolMsg.put("name",         tc.name());
                        toolMsg.put("content",      toolResult);
                        history.add(toolMsg);

                        extractEvents(toolResult, tc.name(), emit);
                    }
                    continue;
                }

                // ── 文本回复 ──────────────────────────────────────────────
                String text = resp.content();
                if (text != null && !text.isBlank()) {
                    history.add(Map.of("role", "assistant", "content", text));
                    emit.accept(ChatEvent.text(text));
                }
                break;
            }

            // ── Host 兜底刷新（AIPP Widget View 协议）────────────────────
            // 若 LLM 调用了 mutating_tools 但未主动调用 refresh_skill，Host 自动补调一次。
            autoRefreshIfNeeded(toolsCalledThisTurn, emit);

            // ── Host 后台记忆整合（fire-and-forget，完全静默）─────────────
            // memory_consolidate 不进 LLM 工具列表，由 Host 在每轮结束后自动异步触发。
            List<Map<String, Object>> turnSnapshot =
                    new ArrayList<>(history.subList(turnStart, history.size()));
            autoConsolidateMemory(turnSnapshot);

        } catch (Exception e) {
            emit.accept(ChatEvent.error("LLM error: " + e.getMessage()));
        } finally {
            this.currentTurnHints = List.of();  // clear per-turn hints after use
        }

        emit.accept(ChatEvent.done());
    }

    // ── internal ──────────────────────────────────────────────────────────

    /**
     * 构建 LLM 上下文窗口：system + 最近 CONTEXT_WINDOW 条消息。
     *
     * <p>System message 按 3 层结构动态组合：
     * <ol>
     *   <li>Layer 1 — worldone system prompt（全局铁律 + app 能力说明）</li>
     *   <li>Layer 2 — session entry prompt（task/event session 专有）</li>
     *   <li>Layer 3 — widget context prompt（canvas 模式下追加）</li>
     * </ol>
     */
    private List<Map<String, Object>> contextWindow() {
        String sysContent = (String) history.get(0).get("content");

        // ── 最高优先级：本轮 UI 上下文（覆盖任何其他指令）──────────────────
        if (!currentTurnHints.isEmpty()) {
            String hintBlock = "## 🔴 当前 UI 上下文（最高优先级，本轮必须遵守）\n"
                    + String.join("\n", currentTurnHints.stream()
                          .map(h -> "- " + h).toArray(String[]::new))
                    + "\n\n";
            sysContent = hintBlock + sysContent;
        }

        if (sessionEntryPrompt != null && !sessionEntryPrompt.isBlank()) {
            sysContent = sysContent + "\n\n---\n" + sessionEntryPrompt;
        }

        if (activeWidgetType != null) {
            String widgetCtx = registry.widgetContextPrompt(activeWidgetType);
            if (widgetCtx != null && !widgetCtx.isBlank()) {
                sysContent = sysContent
                    + "\n\n---\n## 当前 Canvas 模式：" + activeWidgetType + "\n"
                    + widgetCtx;
            }
        }

        List<Map<String, Object>> ctx = new ArrayList<>();
        ctx.add(Map.of("role", "system", "content", sysContent));

        List<Map<String, Object>> rest = history.subList(1, history.size());
        if (rest.size() <= CONTEXT_WINDOW) {
            ctx.addAll(rest);
        } else {
            ctx.addAll(rest.subList(rest.size() - CONTEXT_WINDOW, rest.size()));
        }
        return ctx;
    }

    /**
     * Host 兜底刷新：若本轮调用了 widget 的 mutating_tools 但 LLM 未主动调用 refresh_skill，
     * 则 Host 自动补调一次 refresh_skill，确保 widget 数据展示与实际数据一致。
     *
     * <p>这是 AIPP Widget View 协议的通用机制：
     * <ul>
     *   <li>LLM 主导刷新（通过 ui_hints 中的指令）是第一道保障；</li>
     *   <li>Host 兜底刷新是第二道保障——无论 LLM 是否遵循 hint，数据都会刷新。</li>
     * </ul>
     */
    private void autoRefreshIfNeeded(List<String> toolsCalledThisTurn, Consumer<ChatEvent> emit) {
        if (activeWidgetType == null || toolsCalledThisTurn.isEmpty()) return;

        String refreshSkill = registry.getWidgetRefreshSkill(activeWidgetType);
        if (refreshSkill == null) return;

        // 如果 LLM 已主动调用了 refresh_skill，无需再补调
        if (toolsCalledThisTurn.contains(refreshSkill)) return;

        // 检查是否有 mutating_tool 被调用
        boolean anyMutating = toolsCalledThisTurn.stream()
                .anyMatch(t -> registry.isWidgetMutatingTool(activeWidgetType, t));
        if (!anyMutating) return;

        // 兜底调用 refresh_skill
        try {
            AppRegistration app = registry.findAppForTool(refreshSkill);
            String url = app.toolUrl(refreshSkill);
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("args", Map.of());
            reqBody.put("_context", Map.of(
                "userId",      userId,
                "sessionId",   sessionId,
                "workspaceId", workspaceId != null ? workspaceId : "",
                "agentId",     "worldone"
            ));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(reqBody)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            extractEvents(resp.body(), refreshSkill, emit);
            log.debug("[AutoRefresh] Triggered {} for widget {}", refreshSkill, activeWidgetType);
        } catch (Exception e) {
            log.warn("[AutoRefresh] Failed to call {}: {}", refreshSkill, e.getMessage());
            // silent — auto-refresh failure must not break the chat flow
        }
    }

    /**
     * 将工具调用路由到对应 app 的 HTTP 端点执行。
     *
     * <p>始终向请求体注入 {@code _context}（userId, sessionId, agentId）。
     * 若 skill 声明 {@code inject_context.turn_messages=true}，还注入 turnSnapshot。
     */
    private String callToolViaHttp(LLMCaller.ToolCall tc,
                                    List<Map<String, Object>> turnSnapshot) {
        try {
            AppRegistration app = registry.findAppForTool(tc.name());
            String url = app.toolUrl(tc.name());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("args", tc.parsedArgs());
            body.put("_context", Map.of(
                "userId",         userId,
                "sessionId",      sessionId,
                "workspaceId",    workspaceId   != null ? workspaceId   : "",
                "workspaceTitle", workspaceTitle != null ? workspaceTitle : "",
                "agentId",        "worldone"
            ));

            // inject_context.turn_messages=true：注入完整本轮消息列表（Option B）
            if (registry.requiresTurnMessages(tc.name())) {
                body.put("turn_messages", turnSnapshot);
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();

        } catch (IllegalArgumentException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Tool HTTP call failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 从 Skill 执行结果中提取事件，顺序：SESSION → CANVAS。
     */
    private void extractEvents(String toolResult, String skillName, Consumer<ChatEvent> emit) {
        try {
            JsonNode root = JSON.readTree(toolResult);
            String widgetType = registry.findOutputWidgetForSkill(skillName);

            // ── HTML_WIDGET：Chat 内嵌 HTML 卡片（is_canvas_mode=false）──────────
            if (root.has("html_widget")) {
                JsonNode hw = root.get("html_widget");
                emit.accept(ChatEvent.htmlWidget(hw.toString()));
                return; // html_widget 不触发 canvas/session 事件
            }

            // ── SESSION ───────────────────────────────────────────────────
            // Derive the world/task name early so it's available for both SESSION and CANVAS blocks
            String worldName;
            if (root.has("new_session")) {
                worldName = root.get("new_session").path("name").asText("");
                if (worldName.isBlank()) worldName = widgetType != null ? registry.widgetTitle(widgetType) : "";
            } else {
                worldName = root.path("session_name").isMissingNode() || root.path("session_name").asText().isBlank()
                        ? (widgetType != null ? registry.widgetTitle(widgetType) : "")
                        : root.path("session_name").asText();
            }

            // canvas_session_id: the tool-side session id (e.g. WorldOneSession.id),
            // used by enrichSessionEvent to find-or-create a UiSession.
            String canvasSessionIdForPayload = root.path("session_id").asText("");

            if (root.has("new_session")) {
                JsonNode ns = root.get("new_session");
                String sessionType = ns.path("type").asText("task");
                String welcome = registry.widgetWelcomeMessage(widgetType);

                String payload = "{\"name\":\""               + escapeJson(ns.path("name").asText())
                               + "\",\"type\":\""             + escapeJson(sessionType)
                               + "\",\"agent_session_id\":\"" + escapeJson(sessionId)
                               + (welcome != null ? "\",\"welcome_message\":\"" + escapeJson(welcome) : "")
                               + (!canvasSessionIdForPayload.isBlank()
                                   ? "\",\"canvas_session_id\":\"" + escapeJson(canvasSessionIdForPayload) : "")
                               + "\"}";
                emit.accept(ChatEvent.session(payload));

                // 用 widget 的 welcome_message 作为 Layer-2 session 提示词；
                // 如果没有对应 widget，退回到注册表里的通用 sessionEntryPrompt
                String entryPrompt = welcome != null ? welcome : registry.sessionEntryPrompt(sessionType);
                if (entryPrompt != null && this.sessionEntryPrompt == null) {
                    this.sessionEntryPrompt = entryPrompt;
                }

            } else if (widgetType != null) {
                // 打开已有世界（无 new_session）：同样需要 session 事件，以便前端
                // 切换到对应 task session 并显示欢迎语
                String welcome   = registry.widgetWelcomeMessage(widgetType);
                String payload   = "{\"name\":\""               + escapeJson(worldName)
                                 + "\",\"type\":\"task"
                                 + "\",\"agent_session_id\":\"" + escapeJson(sessionId)
                                 + (welcome != null ? "\",\"welcome_message\":\"" + escapeJson(welcome) : "")
                                 + (!canvasSessionIdForPayload.isBlank()
                                     ? "\",\"canvas_session_id\":\"" + escapeJson(canvasSessionIdForPayload) : "")
                                 + "\"}";
                emit.accept(ChatEvent.session(payload));

                String entryPrompt = welcome != null ? welcome : registry.sessionEntryPrompt("task");
                if (entryPrompt != null && this.sessionEntryPrompt == null) {
                    this.sessionEntryPrompt = entryPrompt;
                }
            }

            // ── CANVAS：优先由 worldone 基于 registry 生成 ─────────────────
            if (widgetType != null) {
                String action       = root.has("new_session") ? "open" : "replace";
                String sessionIdVal = root.path("session_id").asText("");
                JsonNode graph      = root.path("graph");

                Map<String, Object> canvas = new LinkedHashMap<>();
                canvas.put("action",      action);
                canvas.put("widget_type", widgetType);
                if (!sessionIdVal.isBlank()) canvas.put("session_id", sessionIdVal);
                // session_name used by frontend to set the side panel title
                if (!worldName.isBlank()) canvas.put("session_name", worldName);
                if (!graph.isMissingNode() && !graph.isNull()) {
                    canvas.put("props", JSON.treeToValue(graph, Map.class));
                }

                activeWidgetType = widgetType;
                // canvas session_id IS the worldId = workspaceId for this widget instance
                if (!sessionIdVal.isBlank()) {
                    this.workspaceId = sessionIdVal;
                }
                // capture world name as workspace title for consolidation context
                if (!worldName.isBlank()) {
                    this.workspaceTitle = worldName;
                }
                emit.accept(ChatEvent.canvas(JSON.writeValueAsString(canvas)));

            // ── CANVAS：兼容旧格式（tool 响应中含 canvas 字段）─────────────
            } else if (root.has("canvas")) {
                JsonNode canvas = root.get("canvas");
                String action   = canvas.path("action").asText("");
                String wType    = canvas.path("widget_type").asText("");

                if ("close".equals(action)) {
                    activeWidgetType = null;
                } else if (!wType.isBlank()) {
                    activeWidgetType = wType;
                }

                // 对 open/replace 命令补发 session 事件，以便前端创建任务条目
                if (("open".equals(action) || "replace".equals(action)) && !wType.isBlank()) {
                    String sessionIdVal = canvas.path("session_id").asText("");
                    String canvasWorldName = root.path("session_name").asText("");
                    if (canvasWorldName.isBlank()) canvasWorldName = root.path("session_id").asText("");
                    if (canvasWorldName.isBlank()) canvasWorldName = registry.widgetTitle(wType);
                    String welcome  = registry.widgetWelcomeMessage(wType);
                    String payload  = "{\"name\":\""               + escapeJson(canvasWorldName)
                                    + "\",\"type\":\"task"
                                    + "\",\"agent_session_id\":\"" + escapeJson(sessionId)
                                    + (welcome != null ? "\",\"welcome_message\":\"" + escapeJson(welcome) : "")
                                    + "\"}";
                    emit.accept(ChatEvent.session(payload));
                    if (welcome != null && this.sessionEntryPrompt == null) {
                        this.sessionEntryPrompt = welcome;
                    }
                    if (!sessionIdVal.isBlank()) this.workspaceId = sessionIdVal;
                    if (!canvasWorldName.isBlank()) this.workspaceTitle = canvasWorldName;
                }

                emit.accept(ChatEvent.canvas(canvas.toString()));
            }

        } catch (Exception ignored) { }
    }

    /** 合并基础 skills 与当前 canvas widget 的 canvas_skill.tools，按名称去重。 */
    private String mergeCanvasTools(List<Map<String, Object>> baseSkills) {
        if (activeWidgetType == null) return buildToolsJson(baseSkills);
        List<Map<String, Object>> canvasTools = registry.getCanvasTools(activeWidgetType);
        if (canvasTools.isEmpty()) return buildToolsJson(baseSkills);

        Set<String> baseNames = new HashSet<>();
        for (Map<String, Object> s : baseSkills) {
            Object n = s.get("name");
            if (n != null) baseNames.add(n.toString());
        }

        List<Map<String, Object>> merged = new ArrayList<>(baseSkills);
        for (Map<String, Object> ct : canvasTools) {
            Object n = ct.get("name");
            if (n == null || !baseNames.contains(n.toString())) {
                merged.add(ct);
            }
        }
        return buildToolsJson(merged);
    }

    /**
     * 若 LLM 本轮调用了 mutating tools（add/modify/remove）但没有主动调 world_get_design 刷新，
     * Host 自动补一次 world_get_design 以保持 ontology canvas 同步。
     * Decisions / Actions tab 由各工具自身的 canvas patch 负责刷新，无需额外处理。
     */

    /**
     * Host 后台记忆整合：在每轮对话结束后异步调用 memory_consolidate（background skill），
     * 将本轮消息传给 Memory Agent 做静默整合。
     *
     * <p>Fire-and-forget：不等待结果，不向用户暴露任何信息。
     * 若 memory_consolidate 未注册（memory-one 未接入），静默跳过。
     */
    private void autoConsolidateMemory(List<Map<String, Object>> turnMessages) {
        Map<String, Object> skill = registry.findBackgroundSkill("memory_consolidate");
        if (skill == null) return;
        try {
            AppRegistration app = registry.findAppForTool("memory_consolidate");
            String url = app.toolUrl("memory_consolidate");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("args", Map.of());
            body.put("_context", Map.of(
                "userId",         userId,
                "sessionId",      sessionId,
                "workspaceId",    workspaceId   != null ? workspaceId   : "",
                "workspaceTitle", workspaceTitle != null ? workspaceTitle : "",
                "agentId",        "worldone"
            ));
            body.put("turn_messages", turnMessages);

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null) log.warn("[AutoConsolidate] Failed: {}", err.getMessage());
                    else log.debug("[AutoConsolidate] Done: {}", resp.statusCode());
                });
        } catch (IllegalArgumentException e) {
            // memory_consolidate 工具未在 toolIndex 注册，静默跳过
        } catch (Exception e) {
            log.warn("[AutoConsolidate] Error building request: {}", e.getMessage());
        }
    }

    private static String buildToolsJson(List<Map<String, Object>> skills) {
        if (skills.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> skill : skills) {
            if (!first) sb.append(",");
            first = false;
            try {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name",        skill.get("name"));
                fn.put("description", skill.get("description"));
                fn.put("parameters",  skill.get("parameters"));
                sb.append("{\"type\":\"function\",\"function\":")
                  .append(JSON.writeValueAsString(fn))
                  .append("}");
            } catch (Exception ignored) { }
        }
        return sb.append("]").toString();
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
