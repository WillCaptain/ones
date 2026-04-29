package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.twelve.entitir.aip.LLMCaller;
import org.twelve.entitir.aip.world.ChatEvent;
import org.twelve.entitir.ontology.llm.LLMConfig;
import org.twelve.entitir.worldone.skills.SkillDefinition;
import org.twelve.entitir.worldone.skills.SkillRegistry;
import org.twelve.entitir.worldone.skills.SkillRun;

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
    private static final String AAP_TTL_THIS_TURN = "this_turn";
    private static final String AAP_TTL_UNTIL_WIDGET_CLOSE = "until_widget_close";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 若 assistant 纯文本消息（无工具调用）包含这些"操作已完成"短语，
     * 极可能是幻觉响应，在组装 contextWindow 时替换为安全占位文本。
     */
    private static final Set<String> HALLUCINATION_PHRASES = Set.of(
            "已删除", "删除成功", "已清除", "清除成功",
            "已创建", "创建成功", "已完成操作", "已成功删除",
            "已更新", "更新成功", "已修改", "修改成功",
            "已打开", "已进入", "已在界面上打开", "已成功进入",
            "已成功打开", "已为您打开");

    private final String         sessionId;
    private final String         userId;
    private final LLMCaller      llm;
    private final AppRegistry    registry;
    /** Anthropic-style progressive disclosure：catalog 注入 + load_skill meta-tool 拦截的数据源。 */
    private final SkillRegistry  skillRegistry;
    /** 运行时调试开关（{@code worldone.debug.agent_loop}）；关闭时所有 dbg() 近乎零开销。 */
    private final DebugFlags     debugFlags;
    /** 完整对话历史（第 0 条永远是 system prompt）。 */
    private final List<Map<String, Object>> history = new ArrayList<>();

    private String activeWidgetType   = null;
    /**
     * 当前激活的 view id（widget 内多 tab 场景）。前端在每轮请求中通过
     * {@code canvas.active_view} 传入；切 tab 不换 session、不清 history，
     * 仅本轮 system prompt 与 tool list 随 view 重算。
     * 详见 {@code aipp-protocol.md} § 3.2.1.4。
     */
    private String activeView         = null;
    private String sessionEntryPrompt = null;
    /** workspaceId = canvas world ID；canvas 模式下由 WorldOneChatController 注入。 */
    private String workspaceId        = null;
    /** 当前轮次的记忆上下文（preLoadMemoryContext 自动注入，每轮更新）。 */
    private volatile String currentTurnMemoryContext = null;
    /** human-readable workspace title，用于注入 consolidation prompt。 */
    private String workspaceTitle     = null;
    /** 本轮临时 UI 上下文提示（最高优先级，不进入 history，仅作用于当前 chat() 调用）。 */
    private List<String> currentTurnHints = List.of();
    /**
     * 本轮命中的 skill run（progressive disclosure）。
     *
     * <p>由 Controller 在调用 {@code chat()} 前通过 {@link #setCurrentTurnSkillRun(SkillRun)}
     * 设置。{@code contextWindow()} 会把 {@code run.playbook()} 作为最高优先级 system 块注入，
     * {@code mergeCanvasTools()} 会把可见 tools 过滤到 {@code run.skill().toolsWhitelist()}。
     * 本轮 {@code chat()} 结束时自动清零，不跨轮。
     */
    private SkillRun currentTurnSkillRun = null;
    /** AIPP selected by the root router for the current turn. Null means no app is selected yet. */
    private String currentTurnAippAppId = null;
    /** Root router explicitly decided no AIPP owns this turn. */
    private boolean currentTurnNoAippMatch = false;
    /** 命中后 AAP-Post（执行态手册）。 */
    private String activeAapPostPrompt = null;
    private String activeAapPostAppId  = null;
    private String activeAapPostTtl    = AAP_TTL_THIS_TURN;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public GenericAgentLoop(String sessionId, LLMConfig config, AppRegistry registry) {
        this(sessionId, "default", config, registry, null, null);
    }

    public GenericAgentLoop(String sessionId, String userId, LLMConfig config, AppRegistry registry) {
        this(sessionId, userId, config, registry, null, null);
    }

    public GenericAgentLoop(String sessionId, String userId, LLMConfig config,
                            AppRegistry registry, SkillRegistry skillRegistry) {
        this(sessionId, userId, config, registry, skillRegistry, null);
    }

    public GenericAgentLoop(String sessionId, String userId, LLMConfig config,
                            AppRegistry registry, SkillRegistry skillRegistry,
                            DebugFlags debugFlags) {
        this.sessionId     = sessionId;
        this.userId        = userId;
        this.llm           = new LLMCaller(config);
        this.registry      = registry;
        this.skillRegistry = skillRegistry;
        this.debugFlags    = debugFlags;
        history.add(Map.of("role", "system", "content", registry.aggregatedSystemPrompt()));
    }

    // ========================================================================
    // Debug 日志（由 DebugFlags.agent_loop 开关控制）
    // ========================================================================

    /** 是否开启 agent-loop 调试日志；关闭时所有 dbg() 调用早退，几乎零开销。 */
    private boolean dbgOn() {
        return debugFlags != null && debugFlags.isAgentLoopEnabled();
    }

    /** 格式化一条调试日志；仅在 dbgOn() 为 true 时才拼字符串并输出。 */
    private void dbg(String fmt, Object... args) {
        if (!dbgOn()) return;
        log.info("[AgentLoop/DEBUG] session={} " + fmt,
                prependSession(sessionId, args));
    }

    private static Object[] prependSession(String sid, Object[] args) {
        Object[] out = new Object[args.length + 1];
        out[0] = sid;
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }

    /** 截断长字符串用于调试输出，避免日志爆炸。 */
    private static String shorten(String s, int max) {
        if (s == null) return "null";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…(" + oneLine.length() + " chars)";
    }

    private static List<String> toolNames(List<Map<String, Object>> tools) {
        List<String> out = new ArrayList<>(tools.size());
        for (Map<String, Object> t : tools) {
            Object n = t.get("name");
            if (n != null) out.add(n.toString());
        }
        return out;
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

    /**
     * 每轮请求前由 Controller 设置当前激活的 view id（widget 多 tab 场景）。
     * 传 {@code null} 表示无 view 或退回到 widget 级装配。
     */
    public void setActiveView(String viewId) {
        this.activeView = (viewId == null || viewId.isBlank()) ? null : viewId;
    }

    /** 设置当前 canvas world 的 workspaceId（= worldId），注入到所有 _context。 */
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    /**
     * 构建 {@code _context} payload。AIPP tool 调用统一从这里取，避免 5 处副本漂移。
     * 包含 {@code appBaseUrl}：AIPP 自身对外可达地址（host 已在 install 时记录），
     * 用于 AIPP 在 html_widget 里产出指向自己的 iframe URL，无需 AIPP 知道部署细节。
     * 协议参考 README §7.5。
     */
    private Map<String, Object> buildContext(AppRegistration app) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("userId",      userId);
        ctx.put("sessionId",   sessionId);
        ctx.put("workspaceId", workspaceId != null ? workspaceId : "");
        ctx.put("agentId",     "worldone");
        ctx.put("appBaseUrl",  app.baseUrl());
        return ctx;
    }

    /** 设置当前 workspace 的可读名称，用于 consolidation prompt 注入。 */
    public void setWorkspaceTitle(String workspaceTitle) {
        this.workspaceTitle = workspaceTitle;
    }

    /** 设置 session 入场提示词（Layer 2）。 */
    /**
     * 设置本轮命中的 skill run。必须在 {@code chat()} 之前调用；
     * {@code chat()} 结束时自动清零。
     */
    public void setCurrentTurnSkillRun(SkillRun run) {
        this.currentTurnSkillRun = run;
    }

    public SkillRun getCurrentTurnSkillRun() {
        return currentTurnSkillRun;
    }

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
     * 作为 args 传入。这使得从 app 列表打开和 LLM 调用主入口
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
            args = registry.injectEnvVars(app.appId(), args);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("args", args);
            body.put("_context", buildContext(app));
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
        clearTurnScopedAapPostIfNeeded();
        this.currentTurnAippAppId = null;
        this.currentTurnNoAippMatch = false;
        this.currentTurnHints = uiHints != null ? uiHints : List.of();
        history.add(Map.of("role", "user", "content", userMessage));

        dbg("──────── TURN START ────────", (Object[]) new Object[0]);
        dbg("user_msg={} widget={} view={} workspace={}",
                shorten(userMessage, 200), activeWidgetType, activeView, workspaceId);
        if (uiHints != null && !uiHints.isEmpty()) {
            dbg("ui_hints={}", uiHints);
        }

        // ── Host 自动预加载记忆上下文（auto_pre_turn skills，不经 LLM）──────────
        preLoadMemoryContext(userMessage);

        List<Map<String, Object>> tools = new ArrayList<>(registry.allTools());
        if (dbgOn()) {
            dbg("base_tools(count={})={}", tools.size(), toolNames(tools));
        }
        int turnStart = history.size() - 1;
        // Track every tool called this turn, for auto-refresh detection
        List<String> toolsCalledThisTurn = new ArrayList<>();

        try {
            // ── Pass 1 (Skill Router) ────────────────────────────────────
            // 存在可见 skill 时，先走一次"路由" LLM 调用，决定：
            //   (a) load_skill(X)         → 下面 Executor 带 playbook + 白名单
            //   (b) no_skill_matches      → 下面 Executor = flat 模式
            //   (c) universal tool（仅 main session） → 该 tool 已在 Router 内执行并 commit
            //       到 history，Executor 接着把结果总结回复给用户
            // 可见 skill 为空时直接跳过 Router，保持和改造前完全一致的 flat 行为。
            boolean routerHandledTurn =
                    runSkillRouterIfApplicable(userMessage, emit, toolsCalledThisTurn, turnStart);
            if (!routerHandledTurn && currentTurnAippAppId != null && currentTurnSkillRun == null) {
                routerHandledTurn =
                        runSkillRouterIfApplicable(userMessage, emit, toolsCalledThisTurn, turnStart);
            }
            int rounds = 0;
            while (!routerHandledTurn && rounds++ < MAX_TOOL_ROUNDS) {
                String effectiveToolsJson = mergeCanvasTools(tools);
                if (dbgOn()) {
                    dbg("round={} skill_loaded={} effective_tools_json_len={} context_msgs={}",
                            rounds,
                            currentTurnSkillRun == null ? "null"
                                    : currentTurnSkillRun.skill().name(),
                            effectiveToolsJson.length(),
                            history.size());
                }

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
                    String  taskSessionName     = null;   // 非 null 时表示触发了 task session 导航
                    // 本轮已通知的 AIPP（去重，每个 AIPP 只 emit 一次 annotation）
                    java.util.Set<String> announcedAipps = new java.util.HashSet<>();
                    for (LLMCaller.ToolCall tc : resp.toolCalls()) {
                        // 在工具 chip 前先 emit AIPP 注解行（仅首次）
                        String aippLabel = resolveAippLabel(tc.name());
                        if (aippLabel != null && announcedAipps.add(aippLabel)) {
                            emit.accept(ChatEvent.annotation(
                                    "{\"label\":\"" + aippLabel + "\"}"));
                        }
                        emit.accept(ChatEvent.toolCall(tc.name()));
                        toolsCalledThisTurn.add(tc.name());

                        dbg("tool_call#{} name={} args={}",
                                toolsCalledThisTurn.size(), tc.name(), shorten(tc.arguments(), 300));
                        String toolResult;
                        if (LOAD_SKILL_TOOL.equals(tc.name())) {
                            // Anthropic-style progressive disclosure：host 本地拦截，不走 HTTP。
                            toolResult = handleLoadSkillCall(tc);
                            dbg("load_skill INTERCEPT result={}", shorten(toolResult, 400));
                        } else {
                            toolResult = callToolViaHttp(tc, turnSnapshot);
                            dbg("tool_result tool={} len={} preview={}",
                                    tc.name(), toolResult == null ? 0 : toolResult.length(),
                                    shorten(toolResult, 300));
                        }
                        log.info("[ToolCall] tool={} args={}", tc.name(), tc.arguments());
                        log.info("[ToolResult] tool={} result={}", tc.name(), toolResult.length() > 500 ? toolResult.substring(0, 500) + "…" : toolResult);

                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role",         "tool");
                        toolMsg.put("tool_call_id", tc.id());
                        toolMsg.put("name",         tc.name());
                        toolMsg.put("content",      toolResult);
                        history.add(toolMsg);
                        String activatedAppId = applyAapPostFromToolResult(toolResult, tc.name());
                        if (activatedAppId != null && !activatedAppId.isBlank()) {
                            emit.accept(ChatEvent.annotation(
                                    "{\"label\":\"AAP-Post: " + escapeJson(registry.appDisplayName(activatedAppId)) + "\"}"));
                        }

                        String sessionName = extractEvents(toolResult, tc.name(), emit);
                        if (sessionName != null) taskSessionName = sessionName;

                        // html_widget：widget 已渲染到对话，本轮就此结束，
                        // 不再让 LLM 续写文字（否则文字会覆盖 widget）
                        if (isHtmlWidget(toolResult, tc.name(),
                                registry.getOutputWidgetRules(tc.name()))) {
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
                        // html_widget 已渲染到 UI（view/list 类操作）。
                        // 从 LLM history 中完全移除本轮的 tool_call + tool_result：
                        //   - 不留任何占位，使 LLM 对"曾经展示过"毫无记忆
                        //   - 下次用户再次请求时，LLM 会自然地重新调用工具获取最新数据
                        //   - UI 卡片（role=widget, processed 状态）由 DB 独立维护，不受影响
                        if (history.size() > turnStart + 1) {
                            history.subList(turnStart + 1, history.size()).clear();
                        }
                        // 同时移除 user message（本轮提问不进入历史，防止 LLM 记住"我问过了"）
                        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).get("role"))) {
                            history.remove(history.size() - 1);
                        }
                        break;
                    }
                    if (taskSessionName != null) {
                        // Task/world session 已打开，主 session 无需保留完整的 tool 数据。
                        // 将本轮的 tool_call + tool_result（含 world schema 等大量数据）替换为
                        // 一条简短占位，防止主 session 的 LLM 后续被 world 上下文污染。
                        if (history.size() > turnStart + 1) {
                            history.subList(turnStart + 1, history.size()).clear();
                        }
                        history.add(Map.of("role", "assistant",
                                "content", "OK"));
                        log.info("[SessionIsolation] Replaced tool history with placeholder for task session: {}", taskSessionName);
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
                    dbg("assistant_text len={} preview={}", text.length(), shorten(text, 300));
                    history.add(Map.of("role", "assistant", "content", text));
                    emit.accept(ChatEvent.text(text));
                } else {
                    dbg("assistant_text EMPTY (finish_reason={})", resp.finishReason());
                }
                break;
            }
            dbg("──────── TURN END (rounds={}, tools_called={}) ────────",
                    rounds, toolsCalledThisTurn);

            // ── Host 兜底刷新（AIPP Widget View 协议）────────────────────
            // 若 LLM 调用了 mutating_tools 但未主动调用 refresh_skill，Host 自动补调一次。
            autoRefreshIfNeeded(toolsCalledThisTurn, emit);

            // ── Host 后台记忆整合（fire-and-forget，完全静默）─────────────
            // memory_consolidate 不进 LLM 工具列表，由 Host 在每轮结束后自动异步触发。
            List<Map<String, Object>> turnSnapshot =
                    new ArrayList<>(history.subList(turnStart, history.size()));
            triggerPostTurnSkills(turnSnapshot);

        } catch (Exception e) {
            emit.accept(ChatEvent.error("LLM error: " + e.getMessage()));
        } finally {
            this.currentTurnHints = List.of();  // clear per-turn hints after use
            this.currentTurnSkillRun = null;    // clear skill run; one-shot per turn
            clearTurnScopedAapPostIfNeeded();
        }

        emit.accept(ChatEvent.done());
    }

    // ── internal ──────────────────────────────────────────────────────────

    /**
     * 构建 LLM 上下文窗口：一条装配好的 system 消息 + 末尾 CONTEXT_WINDOW 条 history。
     *
     * <p>装配顺序（必须与 {@code docs/aipp-prompt-architecture.md} 「六层提示词结构」一致；
     * 由 {@code PromptLayerOrderingTest} 锁定，任何调整都要同时更新文档与测试）：
     *
     * <ol start="0">
     *   <li>Layer 0 — Host 铁律 + AAP-Pre 聚合（命中时 AAP-Post 替换 AAP-Pre；canvas 激活时追加 Widget Manual / View Prompt）</li>
     *   <li>Layer 1 — Memory Context（用户记忆背景）</li>
     *   <li>Layer 2 — Session Entry Prompt（task/event/app session 专有）</li>
     *   <li>Layer 3 — Widget llm_hint + Workspace info（canvas 激活时）</li>
     *   <li>Layer 4 — Skill Playbook（Router 选中 skill 时）</li>
     *   <li>Layer 5 — UI Hints（前端传 widget_view 时；前置到 sysContent 最前）</li>
     * </ol>
     *
     * <p>Layer 6 = 末尾 CONTEXT_WINDOW 条 history，作为独立 user/assistant/tool 消息追加。
     */
    private List<Map<String, Object>> contextWindow() {
        // ── Layer 0：Host 铁律 + AAP-Pre/AAP-Post + Widget Manual / View Prompt ─────
        String sysContent = buildSystemPromptForTurn();

        // ── Layer 1：Memory Context（user 维度长期画像）──────────────────────────
        if (currentTurnMemoryContext != null && !currentTurnMemoryContext.isBlank()) {
            sysContent = sysContent + "\n\n---\n"
                    + "## 用户记忆背景（内部参考，绝对不要向用户提及或列出）\n"
                    + currentTurnMemoryContext;
        }

        // ── Layer 2：Session Entry Prompt（task/event/app session 专有）──────────
        if (sessionEntryPrompt != null && !sessionEntryPrompt.isBlank()) {
            sysContent = sysContent + "\n\n---\n" + sessionEntryPrompt;
        }

        // ── Layer 3：Widget llm_hint + Workspace info（仅 canvas 激活）──────────
        if (activeWidgetType != null) {
            String widgetCtx = registry.widgetContextPrompt(activeWidgetType);
            String wsContext = "";
            if (workspaceId != null && !workspaceId.isBlank()) {
                wsContext = "**当前 Canvas 工作区**：" + (workspaceTitle != null ? workspaceTitle : workspaceId)
                          + "（session_id: " + workspaceId + "）\n"
                          + "当前 widget 作用域内的工具调用已自动绑定该 session_id；"
                          + "除非工具 schema 明确要求，否则无需重复提供 session_id。\n";
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

        // ── Layer 4：Skill Playbook（Anthropic-style Router → Executor）──────────
        // Router (Pass-1) 选中 load_skill(X) 时，把 X 的完整 playbook 注入；
        // 否则（NO_SKILL / UNIVERSAL_TOOL / SKIPPED）不注入，避免 catalog 重复占用 context。
        if (currentTurnSkillRun != null) {
            String playbook = currentTurnSkillRun.playbook();
            if (playbook != null && !playbook.isBlank()) {
                String skillBlock = "\n\n---\n## Loaded Skill: " + currentTurnSkillRun.skill().name() + "\n"
                        + "This skill was activated by the pre-turn Skill Router. Follow the "
                        + "playbook below strictly and only call tools listed under `allowed-tools`.\n\n"
                        + playbook;
                sysContent = sysContent + skillBlock;
            }
        }

        // ── Layer 5：UI Hints（最高优先级，前置到 sysContent 最前）───────────────
        // 注意：Layer 编号代表"装配阶段"，UI Hints 虽然编号最高，但**写入位置在最前**，
        // 用于覆盖前面所有层的指令（每轮 LLM 必须最先看到）。
        if (!currentTurnHints.isEmpty()) {
            String hintBlock = "## 🔴 当前 UI 上下文（最高优先级，本轮必须遵守）\n"
                    + String.join("\n", currentTurnHints.stream()
                          .map(h -> "- " + h).toArray(String[]::new))
                    + "\n\n";
            sysContent = hintBlock + sysContent;
        }

        List<Map<String, Object>> ctx = new ArrayList<>();
        ctx.add(Map.of("role", "system", "content", sysContent));

        List<Map<String, Object>> rest = sanitizeHistory(history.subList(1, history.size()));
        if (rest.size() <= CONTEXT_WINDOW) {
            ctx.addAll(rest);
        } else {
            ctx.addAll(rest.subList(rest.size() - CONTEXT_WINDOW, rest.size()));
        }

        // DEBUG: dump full LLM context for diagnostics
        if (log.isInfoEnabled()) {
            StringBuilder dump = new StringBuilder("[LLM Context] session=").append(sessionId).append(" messages=").append(ctx.size()).append("\n");
            for (int i = 0; i < ctx.size(); i++) {
                Map<String, Object> msg = ctx.get(i);
                String r = (String) msg.getOrDefault("role", "?");
                Object raw = msg.get("content");
                String c = raw != null ? raw.toString() : "(null)";
                if (msg.containsKey("tool_calls")) c = "(tool_calls)";
                String preview = c.length() > 200 ? c.substring(0, 200) + "…(" + c.length() + " chars)" : c;
                dump.append("  [").append(i).append("] ").append(r).append(": ").append(preview.replace("\n", "\\n")).append("\n");
            }
            log.info(dump.toString());
        }

        return ctx;
    }

    /** 当前回合的 active app：由当前 widget 与最近工具调用推断。 */
    private Set<String> activeAppIdsForTurn() {
        Set<String> active = new LinkedHashSet<>();
        if (activeWidgetType != null && !activeWidgetType.isBlank()) {
            String appId = registry.getWidgetOwnerAppId(activeWidgetType);
            if (appId != null && !appId.isBlank()) active.add(appId);
        }
        for (int i = history.size() - 1; i >= 0 && active.size() < 2; i--) {
            Map<String, Object> msg = history.get(i);
            if (!"tool".equals(String.valueOf(msg.get("role")))) continue;
            String toolName = String.valueOf(msg.getOrDefault("name", ""));
            if (toolName.isBlank()) continue;
            AppRegistration app = registry.findAppForTool(toolName);
            if (app != null && app.appId() != null && !app.appId().isBlank()) {
                active.add(app.appId());
            }
        }
        return active;
    }

    /**
     * 构建当前轮次 Layer 1 system prompt。
     *
     * <p>按优先级三层叠加（见 {@code aipp-prompt-architecture.md}「Widget / View 激活态的 Prompt 装配规则」）：
     * <ol>
     *   <li>Base：Host 基础 + 命中态 AAP-Post（若有），否则 Host 基础 + AAP-Pre 聚合</li>
     *   <li>Widget 激活：追加 {@code widget.system_prompt}</li>
     *   <li>View 激活：追加 {@code view.system_prompt}（仅当 {@code active_view} 匹配 widget 声明的 view）</li>
     * </ol>
     * 不再存在"独立 session"装配分支——widget/view 只是对当前 session 的叠加。
     */
    private String buildSystemPromptForTurn() {
        String base;
        if (activeAapPostPrompt != null && !activeAapPostPrompt.isBlank()) {
            String appLabel = registry.appDisplayName(activeAapPostAppId);
            base = registry.hostSystemPrompt()
                    + "\n\n# " + appLabel + " (AAP-Post)\n"
                    + activeAapPostPrompt.strip()
                    + "\n";
        } else {
            base = registry.aggregatedSystemPrompt(activeAppIdsForTurn());
        }

        if (activeWidgetType == null || activeWidgetType.isBlank()) return base;

        String widgetPrompt = registry.getWidgetSystemPrompt(activeWidgetType);
        if (widgetPrompt != null && !widgetPrompt.isBlank()) {
            base = base + "\n\n# Widget Manual (" + activeWidgetType + ")\n" + widgetPrompt.strip() + "\n";
        }

        if (activeView != null) {
            String viewPrompt = registry.getWidgetViewSystemPrompt(activeWidgetType, activeView);
            if (viewPrompt != null && !viewPrompt.isBlank()) {
                base = base + "\n\n# Current View (" + activeView + ")\n" + viewPrompt.strip() + "\n";
            }
        }
        return base;
    }

    /**
     * Host 兜底刷新：若本轮调用了 widget 的 mutating_tools 但 LLM 未主动调用 refresh_skill，
     * 则 Host 自动补调一次 refresh_skill，并在 args 中传入 {@code session_id = workspaceId}。
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
            // Refresh tools run in the active canvas workspace unless the tool
            // schema asks for a different scope.
            Map<String, Object> refreshArgs = new LinkedHashMap<>();
            if (workspaceId != null && !workspaceId.isBlank()) {
                refreshArgs.put("session_id", workspaceId);
            }
            refreshArgs = registry.injectEnvVars(app.appId(), refreshArgs);
            reqBody.put("args", refreshArgs);
            reqBody.put("_context", buildContext(app));
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
            Map<String, Object> args = registry.injectEnvVars(app.appId(), tc.parsedArgs());
            body.put("args", args);
            Map<String, Object> ctx = buildContext(app);
            ctx.put("workspaceTitle", workspaceTitle != null ? workspaceTitle : "");
            body.put("_context", ctx);

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
     *
     * @return 若触发了 task/world SESSION 导航（需要清理调用方的 tool history），
     *         返回新工作区名称（用于占位消息）；否则返回 null。
     *         app 类型 session 不返回名称（由 html_widget 路径处理，无需清理）。
     */
    private String extractEvents(String toolResult, String skillName, Consumer<ChatEvent> emit) {
        try {
            JsonNode root = JSON.readTree(toolResult);
            String widgetType = registry.findOutputWidgetForSkill(skillName);

            // Host 解耦协议：output_widget_rules.force_canvas_when 列出的字段全部存在且非空时，
            // 强制进入 canvas 模式（即便响应同时带 html_widget）；default_widget 提供兜底类型。
            // 替代旧的 app-specific 字段硬编码特判。
            Map<String, Object> rules = registry.getOutputWidgetRules(skillName);
            boolean forceCanvas = AppRegistry.matchesForceCanvas(root, rules);
            if (widgetType == null && forceCanvas) {
                String dw = registry.getDefaultWidget(skillName);
                if (dw != null && !dw.isBlank()) widgetType = dw;
            }

            // ── HTML_WIDGET：Chat 内嵌 HTML 卡片（is_canvas_mode=false）──────────
            // 满足 force_canvas 时优先 Canvas，避免被 html_widget 分支吞掉。
            if (root.has("html_widget") && !forceCanvas) {
                JsonNode hw = root.get("html_widget");
                emit.accept(ChatEvent.htmlWidget(hw.toString()));
                return null; // html_widget 不触发 canvas/session 事件
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

            // 记录本次 extractEvents 是否触发了 task/world 导航（用于调用方清理 history）
            String taskSessionName = null;

            if (root.has("new_session")) {
                JsonNode ns = root.get("new_session");
                String sessionType = ns.path("type").asText("task");
                String welcome = registry.widgetWelcomeMessage(widgetType);

                // Session handoff payload：widget_type 和 canvas meta 随 payload 下发，
                // 由 host (WorldOneChatController.enrichSessionEvent) 在"新建的 task loop"
                // 上设置；当前 main loop 绝不持有 canvas 字段，防止跨 session 污染。
                String payload = "{\"name\":\""               + escapeJson(ns.path("name").asText())
                               + "\",\"type\":\""             + escapeJson(sessionType)
                               + (welcome != null ? "\",\"welcome_message\":\"" + escapeJson(welcome) : "")
                               + (!canvasSessionIdForPayload.isBlank()
                                   ? "\",\"canvas_session_id\":\"" + escapeJson(canvasSessionIdForPayload) : "")
                               + (widgetType != null ? "\",\"widget_type\":\"" + escapeJson(widgetType) : "")
                               + "\"}";
                emit.accept(ChatEvent.session(payload));

                // 无论 sessionType 是 app 还是 task，只要打开了 canvas 世界，
                // 都需要清理当前 loop 的 tool history，防止上下文污染。
                taskSessionName = worldName.isBlank() ? "新工作区" : worldName;

            } else if (widgetType != null) {
                // 打开已有世界（无 new_session）：同样需要 session 事件，以便前端
                // 切换到对应 task/app session 并显示欢迎语
                String sessionType = root.path("session_type").asText("task");
                String appId       = root.path("app_id").asText("");
                String welcome   = registry.widgetWelcomeMessage(widgetType);
                String payload   = "{\"name\":\""               + escapeJson(worldName)
                                 + "\",\"type\":\""             + escapeJson(sessionType)
                                 + (welcome != null ? "\",\"welcome_message\":\"" + escapeJson(welcome) : "")
                                 + (!canvasSessionIdForPayload.isBlank()
                                     ? "\",\"canvas_session_id\":\"" + escapeJson(canvasSessionIdForPayload) : "")
                                 + (!appId.isBlank() ? "\",\"app_id\":\"" + escapeJson(appId) : "")
                                 + "\",\"widget_type\":\""      + escapeJson(widgetType)
                                 + "\"}";
                emit.accept(ChatEvent.session(payload));

                taskSessionName = worldName.isBlank() ? "工作区" : worldName;
            }

            // ── CANVAS：优先由 worldone 基于 registry 生成 ─────────────────
            if (widgetType != null) {
                String action       = root.has("new_session") ? "open" : "replace";
                String sessionIdVal = root.path("session_id").asText("");
                JsonNode graph      = root.path("graph");
                // world_get_design / 部分 tool 返回顶层 canvas.props，无 graph 字段；
                // 若不合并，会发出无 props 的 replace，前端把图画成空。
                if (graph.isMissingNode() || graph.isNull()) {
                    JsonNode legacyCanvas = root.path("canvas");
                    if (!legacyCanvas.isMissingNode() && legacyCanvas.has("props")) {
                        graph = legacyCanvas.get("props");
                    }
                }

                Map<String, Object> canvas = new LinkedHashMap<>();
                canvas.put("action",      action);
                canvas.put("widget_type", widgetType);
                if (!sessionIdVal.isBlank()) canvas.put("session_id", sessionIdVal);
                // session_name used by frontend to set the side panel title
                if (!worldName.isBlank()) canvas.put("session_name", worldName);
                if (!graph.isMissingNode() && !graph.isNull()) {
                    canvas.put("props", JSON.treeToValue(graph, Map.class));
                }

                // 注意：不再在此处把 activeWidgetType/workspaceId/workspaceTitle 写到
                // this（当前 loop）字段。这些属于"新 task loop"的状态，由 host 在
                // enrichSessionEvent 里对新创建的 loop 显式设置。
                emit.accept(ChatEvent.canvas(JSON.writeValueAsString(canvas)));

            // ── CANVAS：兼容旧格式（tool 响应中含 canvas 字段）─────────────
            } else if (root.has("canvas")) {
                JsonNode canvas = root.get("canvas");
                String action   = canvas.path("action").asText("");
                String wType    = canvas.path("widget_type").asText("");

                // close 是"当前 loop 自己"关掉 canvas 的动作（例如 task loop 里
                // 用户显式关闭 widget）。main loop 从不持有 activeWidgetType，
                // 因此这里的 clearAapPost 只在 task loop 上生效。
                if ("close".equals(action)) {
                    activeWidgetType = null;
                    if (AAP_TTL_UNTIL_WIDGET_CLOSE.equals(activeAapPostTtl)) {
                        clearAapPost();
                    }
                }
                // 注意：open/replace 时不再把 activeWidgetType 写到 this 字段——
                // 那是"新 task loop"的事，由 enrichSessionEvent 处理。

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
                                    + (welcome != null ? "\",\"welcome_message\":\"" + escapeJson(welcome) : "")
                                    + (!sessionIdVal.isBlank()
                                        ? "\",\"canvas_session_id\":\"" + escapeJson(sessionIdVal) : "")
                                    + "\",\"widget_type\":\""      + escapeJson(wType)
                                    + "\"}";
                    emit.accept(ChatEvent.session(payload));
                    // 旧格式 canvas 触发 task session，同样需要清理 history
                    taskSessionName = canvasWorldName.isBlank() ? "工作区" : canvasWorldName;
                }

                emit.accept(ChatEvent.canvas(canvas.toString()));
            }

            return taskSessionName;

        } catch (Exception ignored) { }
        return null;
    }

    /**
     * 合并基础 skills 与当前 canvas widget 的 canvas_skill.tools，按名称去重。
     *
     * <p>当当前 widget 声明 dedicated session 时，进一步按
     * {@code scope.tools_allow / tools_deny / forbid_execution} 对 baseSkills 裁剪；
     * canvas_skill.tools 视为 widget 自带设计态工具，不受 scope 过滤影响。
     */
    // ========================================================================
    // Anthropic-style Skills — Two-Pass Router / Executor
    // ========================================================================
    //
    // Pass-1 (Router) 的唯一职责：在看过用户本轮消息后，从以下三类 action 中强制选一个：
    //
    //   1. load_skill(skill_name)  — 选中 catalog 中的一个 skill 去执行
    //   2. no_skill_matches()       — 本轮没有合适的 skill，交给 Executor 走 flat 模式
    //   3. <universal tool 直接调用> — 仅 main session 暴露，用于一跳就能完成的 navigation
    //                                  类诉求（如 app_list_view）
    //
    // Pass-2 (Executor) 的对话历史：
    //
    //   - 若 Router 选 (1) 且 load_skill 成功：history 只多了用户消息；Executor 的
    //     system prompt 顶部注入 skill playbook，tool list 收窄到 allowed-tools。
    //     Router 自己的 system prompt 和 tool_call 不进 history（纯控制信号，不污染）。
    //   - 若 Router 选 (2)：history 只多了用户消息；Executor 的 system prompt 正常（无
    //     catalog、无 playbook）；tool list = 全量 domain tools。
    //   - 若 Router 选 (3)：Router 的 assistant 消息 + tool_call + tool_result 已经
    //     进 history。Executor 从这里续跑，产出用户能看到的文本汇报。
    //
    // 当 {@link #currentVisibleSkills()} 返回空时，Router 被完全跳过（无需多花一跳
    // LLM 调用），行为回退到改造前的 flat tool-calling。
    // ========================================================================

    /** Router meta-tool 名字：指向一个 skill 去执行。 */
    public static final String LOAD_SKILL_TOOL       = "load_skill";
    /** Router meta-tool 名字：明确声明当前没有合适的 skill。 */
    public static final String NO_SKILL_MATCHES_TOOL = "no_skill_matches";

    /** 当前 UI 位置对主 LLM 可见的 skill catalog。空 registry 时返回空列表。 */
    private List<SkillDefinition> currentVisibleSkills() {
        if (skillRegistry == null) return List.of();
        if (isMainSession()) {
            if (currentTurnAippAppId == null || currentTurnAippAppId.isBlank()) {
                return skillRegistry.appEntrySkills();
            }
            return skillRegistry.visibleSkills(null, null, Set.of(currentTurnAippAppId));
        }
        return skillRegistry.visibleSkills(activeWidgetType, activeView, Set.of());
    }

    /**
     * 是否为 main session（顶层对话，无 canvas widget）。AIPP session 进入某个 widget
     * 之后 {@link #activeWidgetType} 会被设置。
     */
    private boolean isMainSession() {
        return activeWidgetType == null;
    }

    /**
     * 主 session 的 Router 可以"一跳完成"的 universal tools 过滤：
     * 只挑 {@code scope.level == "universal"} 且 {@code visibility} 含 {@code "llm"} 的原子 tool。
     * 这些工具都是一次原子调用就能 100% 满足用户诉求的 navigation / shortcut 类，
     * 不值得包装成 skill（skill 路径比直调多 2 跳 LLM）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> universalLlmToolsForRouter(List<Map<String, Object>> allTools) {
        if (!isMainSession()) return List.of();
        if (currentTurnAippAppId != null && !currentTurnAippAppId.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : allTools) {
            Object scopeObj = t.get("scope");
            if (!(scopeObj instanceof Map<?, ?> scope)) continue;
            Object level = ((Map<String, Object>) scope).get("level");
            if (!"universal".equals(level)) continue;
            Object visObj = t.get("visibility");
            if (visObj instanceof List<?> vis && vis.contains("llm")) {
                out.add(t);
            }
        }
        return out;
    }

    /** 构造 {@link #LOAD_SKILL_TOOL} 的 OpenAI-format schema，包含 catalog name enum。 */
    private Map<String, Object> buildLoadSkillToolSchema(List<SkillDefinition> catalog) {
        List<String> enumNames = new ArrayList<>();
        for (SkillDefinition s : catalog) enumNames.add(s.name());

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("enum", enumNames);
        nameProp.put("description",
                "The exact name of one of the skills listed in 'Available Skills'. "
              + "Call this when the user's request clearly maps to that skill.");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill_name", nameProp);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("skill_name"));
        params.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", LOAD_SKILL_TOOL);
        schema.put("description",
                "Activate a Skill playbook by name. After activation, the Executor will see the "
              + "full playbook and only the skill's allowed-tools for the rest of this turn.");
        schema.put("parameters", params);
        return schema;
    }

    /** 构造 {@link #NO_SKILL_MATCHES_TOOL} 的 schema —— 显式"没有匹配"信号，零参数。 */
    private Map<String, Object> buildNoSkillMatchesToolSchema() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", new LinkedHashMap<>());
        params.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", NO_SKILL_MATCHES_TOOL);
        schema.put("description",
                "Declare that none of the 'Available Skills' match the user's request. "
              + "The Executor will handle it with the full domain tool set (flat mode). "
              + "This is your default choice when unsure.");
        schema.put("parameters", params);
        return schema;
    }

    /**
     * Router 专用 system prompt —— 职责极度单一：读一条用户消息，选一个 action。
     * 不复用主 system（主 system 充满领域规则，Router 不需要也不应看到）。
     */
    private String buildRouterSystemPrompt(
            boolean mainSession,
            List<SkillDefinition> catalog,
            List<Map<String, Object>> universalTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the Skill Router for World One. Your job is to route the user's latest "
                + "message to exactly one action. You do NOT answer the user directly.\n\n");

        sb.append("# Available Skills\n");
        if (catalog.isEmpty()) {
            sb.append("(none at the current location)\n");
        } else {
            for (SkillDefinition s : catalog) {
                sb.append("- **").append(s.name()).append("** — ").append(s.description()).append('\n');
            }
        }
        sb.append('\n');

        if (mainSession && !universalTools.isEmpty()) {
            sb.append("# Universal Tools (main session only — direct one-shot actions)\n");
            for (Map<String, Object> t : universalTools) {
                sb.append("- **").append(t.get("name")).append("** — ")
                  .append(String.valueOf(t.getOrDefault("description", ""))).append('\n');
            }
            sb.append('\n');
        }

        sb.append("# Decision rules (follow in order)\n");
        sb.append("1. If the user's request clearly matches one of the Available Skills, call "
                + "`load_skill` with that skill's exact `name`.\n");
        if (mainSession && !universalTools.isEmpty()) {
            sb.append("2. Else, if the request is a simple operation fully satisfied by one of "
                    + "the Universal Tools above, call that tool directly with the correct arguments.\n");
            sb.append("3. Otherwise call `no_skill_matches` — the default, safe choice when unsure.\n");
        } else {
            sb.append("2. Otherwise call `no_skill_matches` — the default, safe choice when unsure.\n");
        }
        sb.append('\n');
        sb.append("NEVER reply with plain text. Always call exactly one tool. "
                + "When uncertain, prefer `no_skill_matches`.");
        return sb.toString();
    }

    /**
     * 运行 Router。被 {@link #chat} 入口在 Executor 主循环之前调用。
     * 根据 Router 决策修改：
     * <ul>
     *   <li>{@link #currentTurnSkillRun}（若选 load_skill 且加载成功）</li>
     *   <li>{@link #history}（若选 universal tool：加入 assistant 消息 + tool_result）</li>
     *   <li>{@code toolsCalledThisTurn}（若执行了 universal tool）</li>
     *   <li>向前端 emit {@link ChatEvent#annotation Annotation}，让用户看到路由决策</li>
     * </ul>
     * 空 catalog 时直接返回，跳过整个 Router 调用。
     */
    private boolean runSkillRouterIfApplicable(
            String userMessage,
            Consumer<ChatEvent> emit,
            List<String> toolsCalledThisTurn,
            int turnStart) {
        List<SkillDefinition> catalog = currentVisibleSkills();
        if (catalog.isEmpty()) {
            dbg("ROUTER SKIPPED (no visible tools)", (Object[]) new Object[0]);
            return false;
        }

        List<Map<String, Object>> allBaseTools = registry.allTools();
        List<Map<String, Object>> universalTools = universalLlmToolsForRouter(allBaseTools);

        // 组装 Router tool list：load_skill + no_skill_matches + (main session) universal tools
        List<Map<String, Object>> routerTools = new ArrayList<>();
        routerTools.add(buildLoadSkillToolSchema(catalog));
        routerTools.add(buildNoSkillMatchesToolSchema());
        routerTools.addAll(universalTools);
        String routerToolsJson = buildToolsJson(routerTools);

        boolean mainSession = isMainSession();
        String routeLayer = mainSession
                ? (currentTurnAippAppId == null || currentTurnAippAppId.isBlank()
                    ? "root-aipp-router" : "app-skill-router")
                : "widget-skill-router";
        String routerSystem = buildRouterSystemPrompt(mainSession, catalog, universalTools);
        List<Map<String, Object>> routerCtx = List.of(
                Map.of("role", "system", "content", routerSystem),
                Map.of("role", "user",   "content", userMessage)
        );

        List<String> catalogNamesForLog = new ArrayList<>();
        for (SkillDefinition s : catalog) catalogNamesForLog.add(s.name());
        List<String> universalNamesForLog = new ArrayList<>();
        for (Map<String, Object> t : universalTools) {
            Object n = t.get("name");
            if (n != null) universalNamesForLog.add(n.toString());
        }
        log.info("[AIPP_ROUTE] step=router_start layer={} selected_app={} catalog={} universal_tools={} user={}",
                routeLayer,
                currentTurnAippAppId == null ? "" : currentTurnAippAppId,
                catalogNamesForLog,
                universalNamesForLog,
                shorten(userMessage, 160));

        if (dbgOn()) {
            dbg("ROUTER session_type={} catalog={} universal_tools={}",
                    mainSession ? "main" : "aipp", catalogNamesForLog, universalNamesForLog);
        }

        long t0 = System.currentTimeMillis();
        LLMCaller.LLMResponse resp;
        try {
            // tool_choice=required 强制 LLM 调用一个 tool，杜绝"纯文本回复"漏网。
            // 回调传 no-op：Router 的中间输出对用户不可见。
            resp = llm.callStream(
                    routerCtx, routerToolsJson, 512, "required",
                    token -> {}, token -> {});
        } catch (Exception e) {
            log.warn("[SkillRouter] call failed: {} — falling back to flat mode", e.getMessage());
            dbg("ROUTER EXCEPTION {} (fallback to flat)", e.getMessage());
            return false;
        }
        long elapsed = System.currentTimeMillis() - t0;

        List<LLMCaller.ToolCall> calls = resp.toolCalls();
        if (calls == null || calls.isEmpty()) {
            // 罕见：即便 tool_choice=required，也有可能模型强行给文本。
            // 保守降级到 flat，前端 emit 一条告警 annotation。
            dbg("ROUTER NO_TOOL_CALL finish_reason={} latency={}ms (fallback to flat)",
                    resp.finishReason(), elapsed);
            emit.accept(ChatEvent.annotation(
                    "{\"label\":\"Router: degraded (no tool_call) → flat mode\"}"));
            return false;
        }

        LLMCaller.ToolCall tc = calls.get(0);
        String name = tc.name();
        log.info("[AIPP_ROUTE] step=router_decision layer={} tool={} args={} latency_ms={}",
                routeLayer, name, shorten(tc.arguments(), 240), elapsed);

        if (LOAD_SKILL_TOOL.equals(name)) {
            String previousAipp = currentTurnAippAppId;
            String result = handleLoadSkillCall(tc);
            boolean loaded = currentTurnSkillRun != null;
            dbg("ROUTER decision=load_skill args={} loaded={} latency={}ms",
                    shorten(tc.arguments(), 200), loaded, elapsed);
            if (loaded) {
                log.info("[AIPP_ROUTE] step=skill_selected app={} skill={} allowed_tools={}",
                        currentTurnSkillRun.skill().appId(),
                        currentTurnSkillRun.skill().name(),
                        currentTurnSkillRun.skill().allowedTools());
                emit.accept(ChatEvent.annotation(
                        "{\"label\":\"Router: Skill→" + escapeJson(currentTurnSkillRun.skill().name()) + "\"}"));
            } else if (currentTurnAippAppId != null && !Objects.equals(previousAipp, currentTurnAippAppId)) {
                log.info("[AIPP_ROUTE] step=aipp_selected app={} display_name={}",
                        currentTurnAippAppId, registry.appDisplayName(currentTurnAippAppId));
                emit.accept(ChatEvent.annotation(
                        "{\"label\":\"Router: AIPP→" + escapeJson(registry.appDisplayName(currentTurnAippAppId)) + "\"}"));
            } else {
                emit.accept(ChatEvent.annotation(
                        "{\"label\":\"Router: Skill load failed → flat mode (" + escapeJson(shorten(result, 80)) + ")\"}"));
            }
            return false;
        }

        if (NO_SKILL_MATCHES_TOOL.equals(name)) {
            dbg("ROUTER decision=no_skill_matches latency={}ms", elapsed);
            if (isMainSession() && (currentTurnAippAppId == null || currentTurnAippAppId.isBlank())) {
                currentTurnNoAippMatch = true;
                log.info("[AIPP_ROUTE] step=no_aipp_match fallback=host_tools");
                emit.accept(ChatEvent.annotation("{\"label\":\"Router: no AIPP match → host mode\"}"));
            } else {
                log.info("[AIPP_ROUTE] step=no_app_skill_match app={} fallback=app_tools",
                        currentTurnAippAppId == null ? "" : currentTurnAippAppId);
                emit.accept(ChatEvent.annotation("{\"label\":\"Router: no skill match → app tools\"}"));
            }
            return false;
        }

        // Universal tool — 直接在 Router 里执行，commit 到 history，让 Executor 续跑汇报
        dbg("ROUTER decision=universal_tool name={} args={} latency={}ms",
                name, shorten(tc.arguments(), 200), elapsed);
        emit.accept(ChatEvent.annotation(
                "{\"label\":\"Router: direct tool→" + escapeJson(name) + "\"}"));
        history.add(resp.rawAssistantMessage());
        emit.accept(ChatEvent.toolCall(name));
        toolsCalledThisTurn.add(name);
        String toolResult = callToolViaHttp(tc, new ArrayList<>(history));
        dbg("ROUTER universal_tool_result tool={} len={} preview={}",
                name, toolResult == null ? 0 : toolResult.length(), shorten(toolResult, 300));
        Map<String, Object> toolMsg = new LinkedHashMap<>();
        toolMsg.put("role",         "tool");
        toolMsg.put("tool_call_id", tc.id());
        toolMsg.put("name",         name);
        toolMsg.put("content",      toolResult);
        history.add(toolMsg);

        // 与 Executor 主循环保持一致：把 widget / canvas / task-session 等事件
        // 发到前端；否则前端拿不到 html_widget，只会看到 LLM 后续把 HTML 总结成文字。
        String activatedAppId = applyAapPostFromToolResult(toolResult, name);
        if (activatedAppId != null && !activatedAppId.isBlank()) {
            emit.accept(ChatEvent.annotation(
                    "{\"label\":\"AAP-Post: " + escapeJson(registry.appDisplayName(activatedAppId)) + "\"}"));
        }
        extractEvents(toolResult, name, emit);

        // html_widget：widget 已渲染到对话，本轮就此结束。
        // 清空本轮 history（user + tool_call + tool_result），避免 Executor LLM
        // 再次调用并把 HTML 总结成文字，覆盖掉 widget。
        if (isHtmlWidget(toolResult, name, registry.getOutputWidgetRules(name))) {
            if (history.size() > turnStart) {
                history.subList(turnStart, history.size()).clear();
            }
            dbg("ROUTER universal_tool html_widget rendered → skip Executor", (Object[]) new Object[0]);
            return true;
        }
        return false;
    }

    /**
     * 主 LLM（仅 Router）调用 {@code load_skill} 时 host 本地拦截：加载 playbook →
     * 设置 {@link #currentTurnSkillRun}；失败不改状态，返回错误 JSON。
     *
     * @return tool_result content（JSON 字符串，仅供调试/错误日志）
     */
    private String handleLoadSkillCall(LLMCaller.ToolCall tc) {
        if (skillRegistry == null) {
            return "{\"error\":\"skills registry not available\"}";
        }
        String skillName;
        try {
            JsonNode args = JSON.readTree(tc.arguments() == null ? "{}" : tc.arguments());
            skillName = args.path("skill_name").asText(null);
        } catch (Exception e) {
            return "{\"error\":\"malformed arguments: " + escapeJson(e.getMessage()) + "\"}";
        }
        if (skillName == null || skillName.isBlank()) {
            return "{\"error\":\"missing skill_name\"}";
        }
        SkillDefinition matched = null;
        for (SkillDefinition s : currentVisibleSkills()) {
            if (Objects.equals(s.name(), skillName)) { matched = s; break; }
        }
        if (matched == null) {
            return "{\"error\":\"skill not available at current location: "
                    + escapeJson(skillName) + "\"}";
        }
        if (org.twelve.entitir.worldone.skills.SkillRegistry.AIPP_ENTRY_PLAYBOOK.equals(matched.playbookUrl())) {
            this.currentTurnAippAppId = matched.appId();
            this.currentTurnNoAippMatch = false;
            log.info("[SkillRouter] selected AIPP app={} via entry skill={}",
                    matched.appId(), matched.name());
            return "{\"status\":\"selected_aipp\",\"app_id\":\"" + escapeJson(matched.appId()) + "\"}";
        }
        String playbook = skillRegistry.loadPlaybook(matched);
        if (playbook == null || playbook.isBlank()) {
            return "{\"error\":\"playbook empty or not found for skill " + escapeJson(skillName) + "\"}";
        }
        this.currentTurnSkillRun = new SkillRun(matched, Map.of(), playbook);
        log.info("[SkillRouter] activated skill={} allowedTools={}",
                matched.name(), matched.allowedTools());
        return "{\"status\":\"loaded\",\"skill\":\"" + escapeJson(matched.name()) + "\"}";
    }

    private String mergeCanvasTools(List<Map<String, Object>> baseSkills) {
        if (isMainSession() && currentTurnNoAippMatch) {
            List<Map<String, Object>> scoped = registry.toolsForApp("worldone-system");
            log.info("[AIPP_ROUTE] step=executor_tools scope=host app=worldone-system tools={}",
                    toolNames(scoped));
            return buildToolsJson(scoped);
        }
        if (isMainSession() && currentTurnAippAppId != null && !currentTurnAippAppId.isBlank()
                && currentTurnSkillRun == null) {
            List<Map<String, Object>> scoped = registry.toolsForApp(currentTurnAippAppId);
            log.info("[AIPP_ROUTE] step=executor_tools scope=aipp app={} tools={}",
                    currentTurnAippAppId, toolNames(scoped));
            return buildToolsJson(scoped);
        }

        // Skill Playbook 命中时，优先级最高：tools 严格收窄到 whitelist，
        // 跳过 canvas_skill 追加与 widget scope 过滤（playbook 自身已经约束）。
        if (currentTurnSkillRun != null) {
            List<String> whitelist = currentTurnSkillRun.skill().allowedTools();
            if (whitelist != null && !whitelist.isEmpty()) {
                Set<String> allow = new HashSet<>(whitelist);
                List<Map<String, Object>> narrowed = new ArrayList<>();
                for (Map<String, Object> s : baseSkills) {
                    Object n = s.get("name");
                    if (n != null && allow.contains(n.toString())) narrowed.add(s);
                }
                return buildToolsJson(narrowed);
            }
            // 未声明 whitelist：按全量（但仍应尽量声明，避免 playbook 外溢调用）
            return buildToolsJson(baseSkills);
        }

        if (activeWidgetType == null) return buildToolsJson(baseSkills);

        List<Map<String, Object>> filtered = applyWidgetScope(baseSkills);
        List<Map<String, Object>> canvasTools = registry.getCanvasTools(activeWidgetType);
        if (canvasTools.isEmpty()) return buildToolsJson(filtered);

        Set<String> baseNames = new HashSet<>();
        for (Map<String, Object> s : filtered) {
            Object n = s.get("name");
            if (n != null) baseNames.add(n.toString());
        }

        List<Map<String, Object>> merged = new ArrayList<>(filtered);
        for (Map<String, Object> ct : canvasTools) {
            Object n = ct.get("name");
            if (n == null || !baseNames.contains(n.toString())) {
                merged.add(ct);
            }
        }
        return buildToolsJson(merged);
    }

    /**
     * 按 widget + view 级 {@code scope} 声明叠加裁剪工具列表。
     * widget 未激活或未声明 scope 时返回原列表不变。
     * view 级 scope 只能收紧（与 widget 级取交集），不能放宽。
     *
     * <p>{@code widget.canvas_skill.tools}（设计态工具）不在本方法范围内——
     * 由 {@link #mergeCanvasTools} 在之后合并，永不被 scope 过滤。
     */
    private List<Map<String, Object>> applyWidgetScope(List<Map<String, Object>> tools) {
        if (activeWidgetType == null || activeWidgetType.isBlank()) return tools;

        Map<String, Object> wScope = registry.getWidgetScope(activeWidgetType);
        Map<String, Object> vScope = (activeView == null)
                ? null : registry.getWidgetViewScope(activeWidgetType, activeView);
        if (wScope == null && vScope == null) return tools;

        List<String> wAllow = scopeAllow(wScope);
        List<String> wDeny  = scopeDeny(wScope);
        boolean forbidExec  = scopeForbidExec(wScope) || scopeForbidExec(vScope);
        List<String> vAllow = scopeAllow(vScope);
        List<String> vDeny  = scopeDeny(vScope);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : tools) {
            Object n = s.get("name");
            if (n == null) continue;
            String name = n.toString();
            if (!wAllow.isEmpty() && !matchesAny(name, wAllow)) continue;
            if (matchesAny(name, wDeny)) continue;
            if (!vAllow.isEmpty() && !matchesAny(name, vAllow)) continue;
            if (matchesAny(name, vDeny)) continue;
            if (forbidExec && registry.isSkillExecution(name)) continue;
            out.add(s);
        }
        if (log.isDebugEnabled()) {
            log.debug("[WidgetScope] widget={} view={} wAllow={} wDeny={} vAllow={} vDeny={} forbidExec={} filtered={}→{}",
                    activeWidgetType, activeView, wAllow, wDeny, vAllow, vDeny, forbidExec,
                    tools.size(), out.size());
        }
        return out;
    }

    private static List<String> scopeAllow(Map<String, Object> scope) {
        return scope == null ? List.of() : asStringList(scope.get("tools_allow"));
    }

    private static List<String> scopeDeny(Map<String, Object> scope) {
        return scope == null ? List.of() : asStringList(scope.get("tools_deny"));
    }

    private static boolean scopeForbidExec(Map<String, Object> scope) {
        return scope != null && Boolean.TRUE.equals(scope.get("forbid_execution"));
    }

    private static List<String> asStringList(Object node) {
        if (!(node instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null) out.add(o.toString());
        return out;
    }

    /** 通配符匹配（仅支持 {@code *} 与字符串相等）。 */
    private static boolean matchesAny(String name, List<String> patterns) {
        for (String p : patterns) {
            if (p == null || p.isEmpty()) continue;
            if (p.equals(name)) return true;
            if (p.contains("*")) {
                String regex = "^" + java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q") + "$";
                if (name.matches(regex)) return true;
            }
        }
        return false;
    }

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
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("user_message", userMessage != null ? userMessage : "");
                args = registry.injectEnvVars(app.appId(), args);
                body.put("args", args);
                body.put("_context", buildContext(app));
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
     * Host 通用 post-turn 调度：每轮对话结束后异步调用所有声明 {@code lifecycle:"post_turn"}
     * 的 skill，将本轮消息作为上下文传入。Fire-and-forget。
     *
     * <p>替代旧的 memory_consolidate 字符串硬编码。AIPP 通过 skill manifest 的
     * {@code lifecycle} 字段自描述调度时机。
     */
    private void triggerPostTurnSkills(List<Map<String, Object>> turnMessages) {
        var skills = registry.findSkillsByLifecycle("post_turn");
        if (skills.isEmpty()) return;
        for (var entry : skills) {
            AppRegistration app = entry.getKey();
            Map<String, Object> skill = entry.getValue();
            Object nameObj = skill.get("name");
            if (nameObj == null) continue;
            String skillName = nameObj.toString();
            try {
                String url = app.toolUrl(skillName);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("args", registry.injectEnvVars(app.appId(), Map.of()));
                Map<String, Object> ptCtx = buildContext(app);
                ptCtx.put("workspaceTitle", workspaceTitle != null ? workspaceTitle : "");
                body.put("_context", ptCtx);
                body.put("turn_messages", turnMessages);

                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
                http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        if (err != null) log.warn("[PostTurn:{}] Failed: {}", skillName, err.getMessage());
                        else log.debug("[PostTurn:{}] Done: {}", skillName, resp.statusCode());
                    });
            } catch (Exception e) {
                log.warn("[PostTurn:{}] Error: {}", skillName, e.getMessage());
            }
        }
    }

    private static String buildToolsJson(List<Map<String, Object>> tools) {
        if (tools.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> skill : tools) {
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
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String applyAapPostFromToolResult(String toolResult, String fallbackToolName) {
        try {
            JsonNode root = JSON.readTree(toolResult);
            JsonNode hit = root.path("aap_hit");
            if (hit.isMissingNode() || hit.isNull() || !hit.isObject()) return null;

            String postPrompt = hit.path("post_system_prompt").asText("");
            if (postPrompt.isBlank()) return null;

            String appId = hit.path("app_id").asText("");
            if (appId.isBlank()) {
                log.warn("[AAP-Post] Ignored because app_id is missing. tool={}", fallbackToolName);
                return null;
            }

            String ttl = hit.path("ttl").asText(AAP_TTL_THIS_TURN).trim().toLowerCase(Locale.ROOT);
            if (!AAP_TTL_UNTIL_WIDGET_CLOSE.equals(ttl)) {
                ttl = AAP_TTL_THIS_TURN;
            }

            activeAapPostPrompt = postPrompt;
            activeAapPostAppId = appId;
            activeAapPostTtl = ttl;
            log.info("[AAP-Post] Activated app={} ttl={} chars={}", appId, ttl, postPrompt.length());
            return appId;
        } catch (Exception ignored) {
            // Ignore malformed tool payload; keep chat flow resilient.
            return null;
        }
    }

    private void clearTurnScopedAapPostIfNeeded() {
        if (AAP_TTL_THIS_TURN.equals(activeAapPostTtl)) {
            clearAapPost();
        }
    }

    private void clearAapPost() {
        activeAapPostPrompt = null;
        activeAapPostAppId = null;
        activeAapPostTtl = AAP_TTL_THIS_TURN;
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

            // widget 是 UI 专用持久化 role，LLM API 不支持，转为 assistant 占位。
            // 使用系统标注格式，避免 LLM 模仿此文本作为回复。
            if ("widget".equals(role)) {
                result.add(Map.of("role", "assistant", "content", "OK"));
                log.debug("[SanitizeHistory] Converted widget → assistant placeholder (session={})", sessionId);
                continue;
            }

            // 连续 user 消息（孤儿）：在前一条 user 后插入合成 assistant 占位，
            // 避免 LLM 看到连续 user 消息而混乱。用户可见历史（loadHistoryForUi）不受影响。
            if ("user".equals(role) && !result.isEmpty()
                    && "user".equals(result.get(result.size() - 1).get("role"))) {
                result.add(Map.of("role", "assistant", "content", "OK"));
                log.debug("[SanitizeHistory] Inserted synthetic assistant for orphan user msg (session={})", sessionId);
            }

            // 幻觉检测：assistant 纯文本（无 tool_calls），且前一条不是 tool result，
            // 且含有幻觉短语 → 直接从 LLM 上下文中移除（不替换，避免 LLM 模仿替换文本）。
            // 孤儿 user 消息检测器会为丢失回复的 user 消息补位。
            if ("assistant".equals(role) && !msg.containsKey("tool_calls")) {
                boolean precededByToolResult =
                        i > 0 && "tool".equals(msgs.get(i - 1).get("role"));
                if (!precededByToolResult) {
                    String content = msg.get("content") instanceof String s ? s : "";
                    if (isHallucinatedCompletion(content)) {
                        log.debug("[SanitizeHistory] Dropped hallucinated completion (session={}): {}", sessionId,
                                content.length() > 40 ? content.substring(0, 40) + "…" : content);
                        continue; // 直接跳过，不加入 result
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
    /**
     * 是否为「仅 Chat 内嵌卡片、不走 Canvas」的工具结果。
     * 当 force-canvas 规则命中时，应与 extractEvents 一致地视为画布回合。
     */
    /**
     * Host 解耦版：rules（来自 skill 的 {@code output_widget_rules}）若命中 {@code force_canvas_when}
     * 则不视为 html_widget 卡片。{@code rules} 可为空 map（旧行为，纯 html_widget 判断）。
     */
    static boolean isHtmlWidget(String toolResultJson, String toolName, Map<String, Object> rules) {
        if (toolResultJson == null || toolResultJson.isBlank()) return false;
        try {
            JsonNode root = JSON.readTree(toolResultJson);
            if (root.path("html_widget").isMissingNode()) return false;
            if (AppRegistry.matchesForceCanvas(root, rules)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 兼容旧签名（无 rules 上下文时使用）。 */
    static boolean isHtmlWidget(String toolResultJson, String toolName) {
        return isHtmlWidget(toolResultJson, toolName, Map.of());
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

    /**
     * 根据工具名推断所属 AIPP 标签，用于在 UI 中展示灰色注解行。
     * 返回 null 表示不需要注解（Host 内置工具、未注册或当前用户视角无关）。
     *
     * <p>Host 解耦：通过 {@link AppRegistry#findAppForTool} 查到归属 app 的展示名，
     * 不再用 tool 名前缀硬编码到具体 AIPP。
     */
    private String resolveAippLabel(String toolName) {
        if (toolName == null) return null;
        try {
            AppRegistration app = registry.findAppForTool(toolName);
            if (app == null) return null;
            String appId = app.appId();
            if (appId == null || appId.isBlank()) return null;
            // host 自身的内置工具不需注解
            if (appId.startsWith("worldone-")) return null;
            return appId;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
