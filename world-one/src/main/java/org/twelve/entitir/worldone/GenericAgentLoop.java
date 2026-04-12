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
    private static final int CONTEXT_WINDOW  = 30;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 若 assistant 纯文本消息（无工具调用）包含这些"操作已完成"短语，
     * 极可能是幻觉响应，在组装 contextWindow 时替换为安全占位文本。
     */
    private static final Set<String> HALLUCINATION_PHRASES = Set.of(
            "已删除", "删除成功", "已清除", "清除成功",
            "已创建", "创建成功", "已完成操作", "已成功删除",
            "已更新", "更新成功", "已修改", "修改成功");

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
    /** 当前轮次的记忆上下文（preLoadMemoryContext 自动注入，每轮更新）。 */
    private volatile String currentTurnMemoryContext = null;
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

    /** 从内存历史末尾截掉最后 n 条（重问时与 DB 删除同步）。 */
    public void trimHistory(int n) {
        trimHistoryRange(-1, n);
    }

    /** 从内存历史中删除从第 from 条（0-based）开始共 count 条。from=-1 时从末尾删。 */
    public void trimHistoryRange(int from, int count) {
        int size = history.size();
        if (size == 0 || count <= 0) return;
        int start = (from < 0) ? Math.max(0, size - count) : Math.min(from, size);
        int end   = Math.min(start + count, size);
        if (start < end) history.subList(start, end).clear();
    }

    /**
     * 删除最后一个完整 user-turn（从最后一条 user 消息到末尾）。
     * 适用于重问清理：不管工具调用了几次，总能完整移除本轮上下文。
     */
    public void trimLastTurn() {
        // 找最后一条 role=user 的位置，从那里删到末尾
        // 注意：history[0] 是 system prompt，不参与查找
        for (int i = history.size() - 1; i >= 1; i--) {
            Object role = history.get(i).get("role");
            if ("user".equals(role)) {
                history.subList(i, history.size()).clear();
                return;
            }
        }
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

        // ── Host 自动预加载记忆上下文（auto_pre_turn skills，不经 LLM）──────────
        preLoadMemoryContext(userMessage);

        List<Map<String, Object>> tools = registry.allSkillsAsTools();
        int turnStart = history.size() - 1;
        // Track every tool called this turn, for auto-refresh detection
        List<String> toolsCalledThisTurn = new ArrayList<>();

        try {
            int rounds = 0;
            while (rounds++ < MAX_TOOL_ROUNDS) {
                String effectiveToolsJson = mergeCanvasTools(tools);

                // Always stream tokens so markdown renders incrementally from first token
                Consumer<String> textCallback     = token   -> emit.accept(ChatEvent.textToken(token));
                Consumer<String> thinkingCallback = thinking -> emit.accept(ChatEvent.thinking(thinking));

                LLMCaller.LLMResponse resp = llm.callStream(
                        contextWindow(), effectiveToolsJson,
                        LLMCaller.DEFAULT_MAX_TOKENS_TOOLS, "auto",
                        textCallback, thinkingCallback);

                // ── 工具调用 ──────────────────────────────────────────────
                if ("tool_calls".equals(resp.finishReason()) && !resp.toolCalls().isEmpty()) {
                    history.add(resp.rawAssistantMessage());

                    // Snapshot current turn for potential turn_messages injection
                    List<Map<String, Object>> turnSnapshot =
                            new ArrayList<>(history.subList(turnStart, history.size()));

                    boolean awaitingConfirmation = false;
                    boolean htmlWidgetRendered  = false;
                    for (LLMCaller.ToolCall tc : resp.toolCalls()) {
                        emit.accept(ChatEvent.toolCall(tc.name()));
                        toolsCalledThisTurn.add(tc.name());

                        String toolResult = callToolViaHttp(tc, turnSnapshot);
                        log.info("[ToolCall] tool={} args={}", tc.name(), tc.arguments());
                        log.info("[ToolResult] tool={} result={}", tc.name(), toolResult.length() > 500 ? toolResult.substring(0, 500) + "…" : toolResult);

                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role",         "tool");
                        toolMsg.put("tool_call_id", tc.id());
                        toolMsg.put("name",         tc.name());
                        toolMsg.put("content",      toolResult);
                        history.add(toolMsg);

                        extractEvents(toolResult, tc.name(), emit);

                        // html_widget：widget 已渲染到对话，本轮就此结束，
                        // 不再让 LLM 续写文字（否则文字会覆盖 widget）
                        if (isHtmlWidget(toolResult)) {
                            htmlWidgetRendered = true;
                        }

                        // sys.* 确认框：操作挂起，等待用户点击，Host 直接给出提示语，
                        // 不再让 LLM 继续一轮（否则 LLM 会误以为操作已完成）
                        boolean needsConfirm = requiresUserConfirmation(toolResult);
                        log.info("[ConfirmCheck] tool={} requiresUserConfirmation={}", tc.name(), needsConfirm);
                        if (needsConfirm) {
                            awaitingConfirmation = true;
                        }
                    }
                    if (htmlWidgetRendered) {
                        // Widget 已渲染到 UI。
                        // 将 history 里本轮的 tool_call + tool_result 替换为一条简短的
                        // assistant 文字，避免 LLM 在后续对话中认为 widget 仍在屏幕上
                        // 并直接用文字描述，而不是重新调用工具。
                        // 替换范围：history 从 turnStart+1（rawAssistantMessage）到末尾
                        if (history.size() > turnStart + 1) {
                            history.subList(turnStart + 1, history.size()).clear();
                        }
                        history.add(Map.of("role", "assistant",
                                "content", "[已在界面上打开了对应的面板]"));
                        break;
                    }
                    if (awaitingConfirmation) {
                        String confirmMsg = "请在上方确认框中确认操作。";
                        history.add(Map.of("role", "assistant", "content", confirmMsg));
                        // ChatEvent.TEXT 被 Controller 拦截持久化但不转发给 SSE，
                        // 需要用 TEXT_TOKEN 流式方式推送到前端，再用 TEXT 持久化
                        emit.accept(ChatEvent.textToken(confirmMsg));
                        emit.accept(ChatEvent.text(confirmMsg));
                        break;
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
        // Layer 1 每轮动态构建：确保新注册的 builtin app system prompt 立即生效，
        // 不依赖 session 创建时的快照（history.get(0) 仅作初始占位，不再直接使用）。
        String sysContent = registry.aggregatedSystemPrompt();

        // ── 记忆上下文（Layer 0，隐式背景知识，最先注入）────────────────────────
        if (currentTurnMemoryContext != null && !currentTurnMemoryContext.isBlank()) {
            sysContent = "## 用户记忆背景（内部参考，绝对不要向用户提及或列出）\n"
                    + currentTurnMemoryContext + "\n\n---\n" + sysContent;
        }

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
            // 注入当前工作区标识（每轮动态计算，不进入 history），让 LLM 始终知道自己在哪个世界
            String wsContext = "";
            if (workspaceId != null && !workspaceId.isBlank()) {
                wsContext = "**当前世界**：" + (workspaceTitle != null ? workspaceTitle : workspaceId)
                          + "（session_id: " + workspaceId + "）\n"
                          + "session_id 已自动绑定，所有 world_* 工具调用无需提供 session_id。\n";
            }
            if (widgetCtx != null && !widgetCtx.isBlank()) {
                sysContent = sysContent
                    + "\n\n---\n## 当前 Canvas 模式：" + activeWidgetType + "\n"
                    + wsContext
                    + widgetCtx;
            } else if (!wsContext.isBlank()) {
                sysContent = sysContent + "\n\n---\n" + wsContext;
            }
        }

        List<Map<String, Object>> ctx = new ArrayList<>();
        ctx.add(Map.of("role", "system", "content", sysContent));

        List<Map<String, Object>> rest = sanitizeHistory(history.subList(1, history.size()));
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
                // 切换到对应 task/app session 并显示欢迎语
                String sessionType = root.path("session_type").asText("task");
                String appId       = root.path("app_id").asText("");
                // app session 的 agent_session_id 固定为 "app-{appId}"，确保上下文隔离
                String agentIdForSession = ("app".equals(sessionType) && !appId.isBlank())
                        ? "app-" + appId : sessionId;
                String welcome   = registry.widgetWelcomeMessage(widgetType);
                String payload   = "{\"name\":\""               + escapeJson(worldName)
                                 + "\",\"type\":\""             + escapeJson(sessionType)
                                 + "\",\"agent_session_id\":\"" + escapeJson(agentIdForSession)
                                 + (welcome != null ? "\",\"welcome_message\":\"" + escapeJson(welcome) : "")
                                 + (!"app".equals(sessionType) && !canvasSessionIdForPayload.isBlank()
                                     ? "\",\"canvas_session_id\":\"" + escapeJson(canvasSessionIdForPayload) : "")
                                 + (!appId.isBlank() ? "\",\"app_id\":\"" + escapeJson(appId) : "")
                                 + "\"}";
                emit.accept(ChatEvent.session(payload));

                String entryPrompt = welcome != null ? welcome : registry.sessionEntryPrompt(sessionType);
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
                } else if (!wType.isBlank() && !wType.startsWith("sys.")) {
                    // sys.* 是覆盖层/inline card，不改变当前 canvas widget
                    activeWidgetType = wType;
                }

                // 对 open/replace 命令补发 session 事件，以便前端创建任务条目
                if (("open".equals(action) || "replace".equals(action)) && !wType.isBlank()
                        && !wType.startsWith("sys.")) {  // sys.* 不创建 session，不切换模式
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
     * Host 自动预加载记忆上下文（auto_pre_turn=true 的 skill，如 memory_load）。
     *
     * <p>在每轮对话开始、LLM 调用前执行，将结果存入 {@code currentTurnMemoryContext}，
     * 由 {@code contextWindow()} 注入为隐藏的系统背景。LLM 永远看不到 memory_load 工具。
     */
    private void preLoadMemoryContext(String userMessage) {
        currentTurnMemoryContext = null;
        var preTurnSkills = registry.getAutoPreTurnSkills();
        if (preTurnSkills.isEmpty()) return;

        for (var entry : preTurnSkills) {
            AppRegistration app       = entry.getKey();
            Map<String, Object> skill = entry.getValue();
            String toolName = ((List<?>) skill.getOrDefault("tools", List.of()))
                    .stream().findFirst().map(Object::toString).orElse(null);
            if (toolName == null) continue;

            try {
                String url = app.toolUrl(toolName);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("args", Map.of("user_message", userMessage != null ? userMessage : ""));
                body.put("_context", Map.of(
                    "userId",         userId,
                    "sessionId",      sessionId,
                    "workspaceId",    workspaceId != null ? workspaceId : "",
                    "agentId",        "worldone"
                ));
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                JsonNode root = JSON.readTree(resp.body());
                String ctx = root.path("memory_context").asText("");
                if (!ctx.isBlank()) {
                    currentTurnMemoryContext = ctx;
                    log.debug("[MemoryPreLoad] Loaded {} chars for session={}", ctx.length(), sessionId);
                }
            } catch (Exception e) {
                log.debug("[MemoryPreLoad] Skipped ({}): {}", toolName, e.getMessage());
            }
        }
    }

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

    /**
     * 【兜底安全网】清洗历史消息：将"无工具调用但暗示操作已完成"的 assistant 消息替换为中性占位文本。
     *
     * <p><b>主要防线</b>不在这里，而在系统提示：world-one 通用铁律声明"历史=参考，不=已执行"，
     * 各 AIPP App 通过 systemPromptContribution 声明自己的操作规范。
     * 本方法作为最后一道防线，处理 LLM 仍然绕过提示词产生幻觉的极端情况。
     *
     * <p>核心规则：assistant 纯文本消息（无 tool_calls 字段），且其前一条消息不是 tool 结果，
     * 且内容包含 {@link #HALLUCINATION_PHRASES} 中的短语 → 认定为幻觉，替换内容。
     *
     * <p>合法的"操作完成"assistant 消息必定紧跟在一条 role=tool 消息之后，
     * 因此此规则不会误伤正常的工具调用后回复。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeHistory(List<Map<String, Object>> msgs) {
        List<Map<String, Object>> result = new ArrayList<>(msgs.size());
        for (int i = 0; i < msgs.size(); i++) {
            Map<String, Object> msg = msgs.get(i);
            String role = (String) msg.getOrDefault("role", "");

            // widget 是 UI 专用持久化 role，LLM API 不支持，转为 assistant 占位
            if ("widget".equals(role)) {
                result.add(Map.of("role", "assistant", "content", "[已在界面上打开了对应的面板]"));
                log.debug("[SanitizeHistory] Converted widget → assistant placeholder (session={})", sessionId);
                continue;
            }

            if ("assistant".equals(role) && !msg.containsKey("tool_calls")) {
                boolean precededByToolResult =
                        i > 0 && "tool".equals(msgs.get(i - 1).get("role"));
                if (!precededByToolResult) {
                    String content = msg.get("content") instanceof String s ? s : "";
                    if (isHallucinatedCompletion(content)) {
                        Map<String, Object> fixed = new LinkedHashMap<>(msg);
                        fixed.put("content", "好的，我会通过相应的工具来处理你的请求。");
                        result.add(fixed);
                        log.debug("[SanitizeHistory] Replaced hallucinated completion (session={})", sessionId);
                        continue;
                    }
                }
            }
            result.add(msg);
        }
        return result;
    }

    /** 判断 assistant 消息是否包含"幻觉完成"特征短语。 */
    private static boolean isHallucinatedCompletion(String text) {
        if (text == null || text.isBlank()) return false;
        for (String phrase : HALLUCINATION_PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    /**
     * 判断工具响应是否需要等待用户确认（sys.* widget 场景）。
     *
     * <p>检测两种标记（其中一个满足即返回 true）：
     * <ol>
     *   <li>{@code "status": "awaiting_confirmation"} — 工具显式声明挂起</li>
     *   <li>{@code "canvas.widget_type"} 以 {@code "sys."} 开头 — 系统内置交互组件</li>
     * </ol>
     *
     * <p>使用 Jackson 解析，不依赖字符串匹配，可单元测试。
     */
    /** tool 结果含 html_widget 时返回 true：widget 已渲染，不需要 LLM 续写文字。 */
    static boolean isHtmlWidget(String toolResultJson) {
        if (toolResultJson == null || toolResultJson.isBlank()) return false;
        try {
            return !JSON.readTree(toolResultJson).path("html_widget").isMissingNode();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean requiresUserConfirmation(String toolResultJson) {
        if (toolResultJson == null || toolResultJson.isBlank()) return false;
        try {
            JsonNode root = JSON.readTree(toolResultJson);
            // 1. 显式 awaiting_confirmation 状态
            if ("awaiting_confirmation".equals(root.path("status").asText(""))) return true;
            // 2. canvas.widget_type 以 sys. 开头
            JsonNode canvas = root.path("canvas");
            if (!canvas.isMissingNode() && !canvas.isNull()) {
                String wType = canvas.path("widget_type").asText("");
                if (wType.startsWith("sys.")) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
