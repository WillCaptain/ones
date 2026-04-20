package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.twelve.entitir.aip.worldone.ChatEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * World One HTTP 层 — 只做 IO 和 SSE 流推送，所有智能逻辑在 aip 层。
 *
 * <ul>
 *   <li>POST /api/sessions             — 创建新会话，返回 {session_id, agent_session_id}</li>
 *   <li>GET  /api/sessions             — 列出活跃 session 元数据（?all=true 含归档）</li>
 *   <li>GET  /api/sessions/{id}/messages — 返回该 session 的对话历史（供前端恢复显示）</li>
 *   <li>PATCH /api/sessions/{id}/complete — 标记完成</li>
 *   <li>PATCH /api/sessions/{id}/void     — 标记作废</li>
 *   <li>DELETE /api/sessions/{id}         — 删除（仅 conversation 类型）</li>
 *   <li>POST /api/chat                 — 发送消息，SSE 流式返回事件</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class WorldOneChatController {

    private static final Logger log = LoggerFactory.getLogger(WorldOneChatController.class);

    @Autowired WorldOneSessionStore agentStore;
    @Autowired UiSessionStore       uiStore;
    @Autowired MessageHistoryStore  messageHistory;
    @Autowired AppRegistry          registry;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // ── 会话管理 ────────────────────────────────────────────────────────────

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> body) {
        String type = body != null ? body.getOrDefault("type", "conversation") : "conversation";
        String name = body != null ? body.getOrDefault("name", "新对话") : "新对话";
        String agentId = agentStore.newSession();
        UiSession ui = uiStore.create(type, name, agentId);
        return Map.of("session_id", ui.id(), "agent_session_id", agentId);
    }

    @GetMapping("/sessions")
    public Map<String, Object> listSessions(@RequestParam(name = "all", defaultValue = "false") boolean all) {
        // app sessions are never included in the main 'sessions' list (they live in 'app_sessions')
        List<UiSession> list = all
                ? uiStore.listAll().stream().filter(s -> !s.isApp()).toList()
                : uiStore.listActive();
        // app sessions returned separately for frontend sessionData initialization
        List<UiSession> apps = uiStore.listApps();
        return Map.of("sessions", list, "app_sessions", apps);
    }

    /**
     * GET /api/sessions/{id}/messages
     *
     * <p>返回该 session 的对话历史（从 DB 读取），供前端在切换 session 时恢复显示。
     * 返回格式：{ "messages": [ { "role": "user"|"assistant", "content": "..." }, ... ] }
     */
    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<Map<String, Object>> getSessionMessages(@PathVariable("id") String id) {
        List<Map<String, Object>> msgs = messageHistory.loadHistoryForUi(id);
        return ResponseEntity.ok(Map.of("messages", msgs));
    }

    /**
     * DELETE /api/sessions/{id}/messages — 清空该 session 的对话历史。
     *
     * <p>同时清除 DB 持久化记录和内存中的 GenericAgentLoop，
     * 下次对话时将以全新 loop（含最新 system prompt）重建。
     * 主 session（id="main"）也可清空。
     */
    @DeleteMapping("/sessions/{id}/messages")
    public ResponseEntity<Map<String, Object>> clearSessionMessages(@PathVariable("id") String id) {
        UiSession ui = uiStore.find(id);
        if (ui == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "session not found"));
        }
        agentStore.resetSession(ui.agentSessionId());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * DELETE /api/sessions/{id}/messages/last?count=N
     *
     * <p>删除该 session 最后 N 条消息（默认 2，即一问一答）。
     * 用于重问时从 DB 末尾移除旧记录，确保刷新后历史正确。
     */
    @DeleteMapping("/sessions/{id}/messages/last")
    public ResponseEntity<Map<String, Object>> deleteLastMessages(
            @PathVariable("id") String id,
            @RequestParam(value = "count", defaultValue = "2") int count) {
        UiSession ui = uiStore.find(id);
        if (ui == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "session not found"));
        }
        // 按"最后一个 user-turn"语义删除，确保工具调用轮次（3+条）也能完整清理
        messageHistory.deleteLastTurn(id, ui.agentSessionId());
        agentStore.trimLastTurn(ui.agentSessionId());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * DELETE /api/sessions/{id}/messages/range?from=N&count=M
     *
     * <p>删除该 session 从第 N 条（0-based）开始的 M 条消息。
     * 用于删除任意位置的消息（含中间消息），刷新后不再显示被删消息。
     */
    @DeleteMapping("/sessions/{id}/messages/range")
    public ResponseEntity<Map<String, Object>> deleteRangeMessages(
            @PathVariable("id") String id,
            @RequestParam("from") int from,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        UiSession ui = uiStore.find(id);
        if (ui == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "session not found"));
        }
        messageHistory.deleteRange(id, ui.agentSessionId(), from, count);
        agentStore.trimHistoryRange(ui.agentSessionId(), from, count);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * PATCH /api/sessions/{id}/messages/widget-processed
     *
     * <p>将该 ui session 最后一条 widget 消息标记为已处理（processed:true 写入 DB）。
     * 前端在 openAppDirect / 切换到 task session 时调用，保证刷新后仍显示"已处理"卡片。
     */
    @org.springframework.web.bind.annotation.PatchMapping("/sessions/{id}/messages/widget-processed")
    public ResponseEntity<Map<String, Object>> markWidgetProcessed(
            @PathVariable("id") String id) {
        UiSession ui = uiStore.find(id);
        if (ui == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "session not found"));
        }
        messageHistory.markLastWidgetProcessed(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PatchMapping("/sessions/{id}/complete")
    public ResponseEntity<Map<String, Object>> completeSession(@PathVariable("id") String id) {
        boolean ok = uiStore.complete(id);
        return ResponseEntity.ok(Map.of("ok", ok));
    }

    @PatchMapping("/sessions/{id}/void")
    public ResponseEntity<Map<String, Object>> voidSession(@PathVariable("id") String id) {
        boolean ok = uiStore.voidSession(id);
        return ResponseEntity.ok(Map.of("ok", ok));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable("id") String id) {
        boolean ok = uiStore.delete(id);
        return ok
            ? ResponseEntity.ok(Map.of("ok", true))
            : ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "无法删除主 session"));
    }

    @PatchMapping("/sessions/{id}/archive")
    public ResponseEntity<Map<String, Object>> archiveSession(@PathVariable("id") String id) {
        boolean ok = uiStore.archive(id);
        return ok
            ? ResponseEntity.ok(Map.of("ok", true))
            : ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "无法归档该 session"));
    }

    @PatchMapping("/sessions/{id}/restore")
    public ResponseEntity<Map<String, Object>> restoreSession(@PathVariable("id") String id) {
        boolean ok = uiStore.restore(id);
        return ok
            ? ResponseEntity.ok(Map.of("ok", true))
            : ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "无法恢复该 session"));
    }

    // ── Apps 启动面板 ─────────────────────────────────────────────────────────

    /**
     * GET /api/apps — 返回所有已注册 AIPP 应用的 manifest 列表（含 main_widget_type）。
     * 供前端 Apps 面板渲染应用图标、名称、描述、颜色。
     */
    @GetMapping("/apps")
    public Map<String, Object> listApps() {
        return Map.of("apps", registry.buildAppsManifests());
    }

    /**
     * POST /api/apps/{appId}/open — 从 Apps 面板直接打开应用主 widget，绕过 LLM。
     *
     * <p>流程：
     * <ol>
     *   <li>找到 appId 对应的 is_main=true widget</li>
     *   <li>找到该 widget 的 renders_output_of_skill（入口 skill）</li>
     *   <li>直接调用该 skill（注入 _context），通过 SSE 流返回事件</li>
     * </ol>
     *
     * <p>可选字段 {@code tool_name} + {@code tool_args}：跳过 main widget 查找，
     * 直接调用指定 skill（如 "world_design"）并传入给定参数。用于 html_widget
     * 内的 postMessage 动作（点击世界卡片进入世界），与 LLM 调用路径完全一致。
     *
     * <p>遵循 "只关注是否有 new_session" 原则：若 skill 响应不含 new_session，
     * 不在 Task Panel 产生 task 条目（和从 chatbot 命中走一样的逻辑）。
     */
    @PostMapping(value = "/apps/{appId}/open", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter openApp(@PathVariable("appId") String appId,
                              @RequestBody(required = false) Map<String, Object> body) {
        String uiSessionId = body != null ? (String) body.getOrDefault("session_id", "main") : "main";
        String userId      = body != null ? (String) body.getOrDefault("userId", "default") : "default";

        // 可选：直接调用指定 skill（html_widget 内卡片点击 postMessage → 进入世界）
        @SuppressWarnings("unchecked")
        Map<String, Object> toolArgs = body != null && body.get("tool_args") instanceof Map<?,?> m
                ? (Map<String, Object>) m : null;
        String toolName = body != null ? (String) body.get("tool_name") : null;

        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> {
            try {
                // Use the calling session's loop for the initial tool call (data fetch).
                // Session routing to app-{appId} is handled by extractEvents + enrichSessionEvent
                // via the session_type=app signal in the tool response.
                UiSession ui = uiStore.find(uiSessionId);
                String agentSessionId = ui != null ? ui.agentSessionId()
                        : agentStore.newSession();

                GenericAgentLoop loop = agentStore.get(agentSessionId);

                if (toolName != null && !toolName.isBlank()) {
                    // 直接调用指定 skill（与 LLM 调用路径相同，经 extractEvents 处理 canvas 事件）
                    Map<String, Object> extraArgs = new java.util.LinkedHashMap<>();
                    extraArgs.put("_tool", toolName);
                    if (toolArgs != null) extraArgs.putAll(toolArgs);
                    String[] currentUiId = { uiSessionId };
                    loop.openApp(appId, extraArgs, ev -> {
                        ChatEvent toSend = ev;
                        if (ev.type() == org.twelve.entitir.aip.worldone.ChatEvent.Type.TEXT) return;
                        try {
                            if (ev.type() == org.twelve.entitir.aip.worldone.ChatEvent.Type.SESSION) {
                                toSend = enrichSessionEvent(ev);
                                String newUiId = extractUiSessionId(toSend);
                                if (newUiId != null) currentUiId[0] = newUiId;
                            } else if (ev.type() == org.twelve.entitir.aip.worldone.ChatEvent.Type.CANVAS) {
                                persistCanvasToSession(currentUiId[0], ev.content());
                            }
                            emitter.send(SseEmitter.event().data(toSend.toSseData()));
                        }
                        catch (Exception ignored) {}
                    });
                } else {
                    // openApp without specific toolName: still needs enrichSessionEvent for SESSION events
                    String[] currentUiId = { uiSessionId };
                    loop.openApp(appId, ev -> {
                        ChatEvent toSend = ev;
                        if (ev.type() == org.twelve.entitir.aip.worldone.ChatEvent.Type.TEXT) return;
                        try {
                            if (ev.type() == org.twelve.entitir.aip.worldone.ChatEvent.Type.SESSION) {
                                toSend = enrichSessionEvent(ev);
                                String newUiId = extractUiSessionId(toSend);
                                if (newUiId != null) currentUiId[0] = newUiId;
                            } else if (ev.type() == org.twelve.entitir.aip.worldone.ChatEvent.Type.CANVAS) {
                                persistCanvasToSession(currentUiId[0], ev.content());
                            }
                            emitter.send(SseEmitter.event().data(toSend.toSseData()));
                        }
                        catch (Exception ignored) {}
                    });
                }

                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(
                            "{\"type\":\"error\",\"content\":\"" + e.getMessage() + "\"}"));
                } catch (Exception ignored) {}
                emitter.complete();
            }
        });
        return emitter;
    }

    // ── 聊天（SSE 流）────────────────────────────────────────────────────────

    /**
     * POST /api/chat
     * Body: { "session_id": "...", "message": "..." }
     * Response: text/event-stream，每条 SSE data 为 ChatEvent JSON
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        String uiSessionId = (String) body.getOrDefault("session_id", "main");
        String message     = (String) body.getOrDefault("message", "");

        // ── AIPP Widget View 协议：前端上报 (widget_type, view_id)，
        //    后端通过 AppRegistry 查 widget manifest 中的 llm_hint，组装最高优先级 ui_hints。
        //    这是泛化的通用机制，不依赖任何 widget 的特殊逻辑。
        @SuppressWarnings("unchecked")
        Map<String, Object> widgetView = body.get("widget_view") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        String wvWidgetType = widgetView != null ? (String) widgetView.get("widget_type") : null;
        String wvViewId     = widgetView != null ? (String) widgetView.get("view_id")     : null;
        List<String> uiHints = registry.buildUiHints(wvWidgetType, wvViewId);

        // 通过 UiSession → agentSessionId 找到对应 GenericAgentLoop
        UiSession ui = uiStore.find(uiSessionId);
        String agentSessionId = ui != null ? ui.agentSessionId() : uiSessionId;
        GenericAgentLoop loop = agentStore.get(agentSessionId);

        // 若 UiSession 处于 Canvas 模式，确保 loop 的 activeWidgetType 已恢复
        // （服务重启后 loop 是全新的，activeWidgetType 默认为 null）。
        // 硬护栏：main session 永远是 chat-mode，不得恢复任何 canvas 状态。
        if (ui != null && ui.hasCanvas() && !"main".equals(ui.id())) {
            loop.setActiveWidgetType(ui.widgetType());
            if (ui.canvasSessionId() != null) {
                loop.setWorkspaceId(ui.canvasSessionId());
            }
            if (ui.name() != null && !ui.name().isBlank()) {
                loop.setWorkspaceTitle(ui.name());
            }
        }
        // 每轮请求都同步当前 active_view（可空），供 widget/view 级 prompt/tool 装配
        loop.setActiveView(wvViewId);

        // 若非 conversation session 且 sessionEntryPrompt 尚未注入，重启后恢复（Layer 2）。
        // 同样不对 main session 注入 session entry prompt。
        if (ui != null && !ui.isConversation() && !"main".equals(ui.id())
                && loop.getSessionEntryPrompt() == null) {
            String entryPrompt = registry.sessionEntryPrompt(ui.type());
            if (entryPrompt != null) {
                loop.setSessionEntryPrompt(entryPrompt);
            }
        }

        // 若 task session 有 workspaceId，异步注册协作参与记录（幂等）
        if (ui != null && !ui.isConversation() && ui.canvasSessionId() != null) {
            registerWorkspaceParticipation(ui.canvasSessionId(), ui.name(), "default", registry);
        }

        SseEmitter emitter = new SseEmitter(180_000L);

        final String finalUiSessionId    = uiSessionId;
        final String finalAgentSessionId = agentSessionId;
        final String finalMessage        = message;
        final List<String> finalUiHints  = uiHints;

        executor.submit(() -> {
            try {
                // 1. 持久化用户消息
                messageHistory.save(finalAgentSessionId, finalUiSessionId, "user", finalMessage);

                // 2. 调用 LLM（回调模式，事件实时推送）
                String[] currentUiId = { finalUiSessionId };

                // Anthropic-style Skills：host 不再做关键词召回。
                // GenericAgentLoop 在 chat() 内部按当前 UI 位置构造 Available Skills catalog 注入
                // system prompt，并暴露 load_skill meta-tool；主 LLM 自己决定是否激活 skill。

                loop.chat(finalMessage, finalUiHints, event -> {
                    ChatEvent toSend = event;
                    try {
                        if (event.type() == ChatEvent.Type.TEXT) {
                            // TEXT 是后端持久化信号，只存历史，不下发前端
                            messageHistory.save(finalAgentSessionId, currentUiId[0], "assistant", event.content());
                            return;   // ← 不 forward 给 SSE

                        } else if (event.type() == ChatEvent.Type.HTML_WIDGET) {
                            // Widget 持久化：存 role='widget' 供刷新后恢复
                            messageHistory.save(finalAgentSessionId, currentUiId[0], "widget", event.content());
                            // 正常 forward 给前端，让前端渲染 iframe

                        } else if (event.type() == ChatEvent.Type.SESSION) {
                            // Chat 流程：app 类型降级为 task，使 LLM 发起的 session 出现在 Task Panel。
                            // openApp 流程保持 app 类型（见 openApp 方法中的回调）。
                            toSend = enrichSessionEvent(downgradeAppToTask(event));
                            String newUiId = extractUiSessionId(toSend);
                            if (newUiId != null) currentUiId[0] = newUiId;

                        } else if (event.type() == ChatEvent.Type.CANVAS) {
                            // 持久化 canvas 状态到 UiSession
                            persistCanvasToSession(currentUiId[0], event.content());
                        }

                        emitter.send(
                            SseEmitter.event().data(toSend.toSseData(), MediaType.APPLICATION_JSON)
                        );
                    } catch (Exception sendEx) {
                        // 客户端断开时静默忽略（emitter 会在 complete/error 时处理）
                        throw new RuntimeException(sendEx);
                    }
                });

                emitter.complete();
            } catch (Exception ex) {
                try {
                    ChatEvent err = ChatEvent.error(ex.getMessage());
                    emitter.send(SseEmitter.event().data(err.toSseData(), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    // ── internal ─────────────────────────────────────────────────────────────

    /**
     * 解析 SESSION 事件 JSON，在 UiSessionStore 中找到或创建 session，
     * 然后返回包含 ui_session_id 的增强事件。
     *
     * <p>若事件携带 canvas_session_id，先按此 id 查找已有 UiSession（幂等），
     * 避免点击同一个已有世界时重复创建任务条目。
     */
    private ChatEvent enrichSessionEvent(ChatEvent e) {
        try {
            JsonNode n              = JSON.readTree(e.content());
            String name             = n.path("name").asText("新任务");
            String type             = n.path("type").asText("task");
            String welcomeMsg       = n.path("welcome_message").asText("");
            String canvasSessionId  = n.path("canvas_session_id").asText("");
            String appId            = n.path("app_id").asText("");
            String widgetType       = n.path("widget_type").asText("");

            UiSession ui;
            if ("app".equals(type) && !appId.isBlank()) {
                // app session：支持单实例/多实例。canvas_session_id 非空时按 (app_id, session_id) 路由
                ui = uiStore.ensureApp(appId, name, canvasSessionId.isBlank() ? null : canvasSessionId);
            } else {
                // find-or-create：若已有对应 canvas_session_id 的 UiSession，直接复用
                UiSession existing = canvasSessionId.isBlank()
                        ? null : uiStore.findByCanvasSessionId(canvasSessionId);
                if (existing != null && !"main".equals(existing.id())) {
                    ui = existing;
                    // chat 流程可能将 app session 降级为 task，同步更新 DB
                    if (!type.equals(existing.type())) {
                        uiStore.updateType(existing.id(), type);
                    }
                } else {
                    // 每个新 task/world session 都分配独立 agent session，避免与 main 或其他世界共享 LLM 上下文
                    String freshAgentId = agentStore.newSession();
                    ui = uiStore.create(type, name, freshAgentId);
                }
            }

            // ── 关键：canvas 字段在【新 task loop】上显式设置，
            //    而不是在当前（触发事件的）loop 上。之前 GenericAgentLoop.extractEvents
            //    直接写自身字段，导致 main loop 被污染成"伪 canvas"。─────────────────
            if (!"main".equals(ui.id())) {
                GenericAgentLoop taskLoop = agentStore.get(ui.agentSessionId());
                if (taskLoop != null) {
                    if (!widgetType.isBlank()) {
                        taskLoop.setActiveWidgetType(widgetType);
                    }
                    if (!canvasSessionId.isBlank()) {
                        taskLoop.setWorkspaceId(canvasSessionId);
                    }
                    if (!name.isBlank()) {
                        taskLoop.setWorkspaceTitle(name);
                    }
                    if (!welcomeMsg.isBlank() && taskLoop.getSessionEntryPrompt() == null) {
                        taskLoop.setSessionEntryPrompt(welcomeMsg);
                    } else if (welcomeMsg.isBlank() && taskLoop.getSessionEntryPrompt() == null) {
                        String fallback = registry.sessionEntryPrompt(type);
                        if (fallback != null) taskLoop.setSessionEntryPrompt(fallback);
                    }
                }

                // 同步 UiSession 的 canvas 字段，下次 chat 路由到此 session 能恢复
                if (!widgetType.isBlank()) {
                    uiStore.updateCanvas(
                        ui.id(),
                        widgetType,
                        canvasSessionId.isBlank() ? null : canvasSessionId
                    );
                }
            }

            String payload = "{\"ui_session_id\":\"" + escapeJson(ui.id())
                           + "\",\"name\":\""         + escapeJson(name)
                           + "\",\"type\":\""         + escapeJson(type)
                           + (welcomeMsg.isBlank() ? "" :
                                 "\",\"welcome_message\":\"" + escapeJson(welcomeMsg))
                           + "\"}";
            return ChatEvent.session(payload);
        } catch (Exception ex) {
            log.warn("[enrichSessionEvent] Failed to enrich session event: {}", ex.getMessage(), ex);
            return e;
        }
    }

    /**
     * 从增强后的 SESSION 事件中提取 ui_session_id。
     */
    private String extractUiSessionId(ChatEvent sessionEvent) {
        try {
            JsonNode n = JSON.readTree(sessionEvent.content());
            String id = n.path("ui_session_id").asText("");
            return id.isBlank() ? null : id;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 解析 CANVAS 事件，将 widgetType 和 canvasSessionId 持久化到 UiSession。
     *
     * <ul>
     *   <li>action=open/replace → 更新 widgetType + canvasSessionId</li>
     *   <li>action=close        → 清除（Canvas 退出为 Chat 模式）</li>
     * </ul>
     */
    private void persistCanvasToSession(String uiSessionId, String canvasJson) {
        try {
            JsonNode canvas = JSON.readTree(canvasJson);
            String action   = canvas.path("action").asText("");

            if ("close".equals(action)) {
                uiStore.updateCanvas(uiSessionId, null, null);
            } else if ("open".equals(action) || "replace".equals(action)) {
                String widgetType     = canvas.path("widget_type").asText("");
                String canvasSessionId = canvas.path("session_id").asText("");
                if (!widgetType.isBlank()) {
                    uiStore.updateCanvas(
                        uiSessionId,
                        widgetType,
                        canvasSessionId.isBlank() ? null : canvasSessionId
                    );
                }
            }
        } catch (Exception ignored) { }
    }

    /**
     * Chat 流程专用：将 SESSION 事件中的 type="app" 降级为 "task"，
     * 移除 app_id，使 enrichSessionEvent 走 task 路径（按 canvas_session_id 查找/创建）。
     * 这样 LLM 发起的 session 会出现在 Task Panel，
     * 而 openApp 流程直接调用 enrichSessionEvent 保持 app 类型。
     */
    private ChatEvent downgradeAppToTask(ChatEvent e) {
        try {
            JsonNode n = JSON.readTree(e.content());
            if (!"app".equals(n.path("type").asText())) return e;
            var copy = (com.fasterxml.jackson.databind.node.ObjectNode) n.deepCopy();
            copy.put("type", "task");
            copy.remove("app_id");
            return ChatEvent.session(JSON.writeValueAsString(copy));
        } catch (Exception ignored) {
            return e;
        }
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Fire-and-forget HTTP call to memory-one's /api/memory_workspace_join.
     * Records that this user has participated in the given workspace (idempotent).
     */
    private void registerWorkspaceParticipation(String workspaceId, String workspaceTitle,
                                                 String userId, AppRegistry reg) {
        executor.submit(() -> {
            try {
                AppRegistration app = reg.findAppForTool("memory_workspace_join");
                if (app == null) return;
                String url = app.toolUrl("memory_workspace_join");
                Map<String, Object> args = new java.util.LinkedHashMap<>();
                args.put("workspace_id", workspaceId);
                args.put("workspace_title", workspaceTitle != null ? workspaceTitle : workspaceId);
                args = reg.injectEnvVars(app.appId(), args);
                Map<String, Object> body = Map.of(
                    "args", args,
                    "_context", Map.of(
                        "userId",      userId,
                        "workspaceId", workspaceId,
                        "agentId",     "worldone"
                    )
                );
                java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                    .build();
                http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) { }
        });
    }
}
