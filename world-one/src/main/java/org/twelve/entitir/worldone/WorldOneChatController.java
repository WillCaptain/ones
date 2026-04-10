package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        List<UiSession> list = all ? uiStore.listAll() : uiStore.listActive();
        return Map.of("sessions", list);
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
                    loop.openApp(appId, ev -> {
                        if (ev.type() == org.twelve.entitir.aip.worldone.ChatEvent.Type.TEXT) return;
                        try { emitter.send(SseEmitter.event().data(ev.toSseData())); }
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
        // （服务重启后 loop 是全新的，activeWidgetType 默认为 null）
        if (ui != null && ui.hasCanvas()) {
            loop.setActiveWidgetType(ui.widgetType());
            // 恢复 workspaceId（= canvasSessionId = worldId）
            if (ui.canvasSessionId() != null) {
                loop.setWorkspaceId(ui.canvasSessionId());
            }
            // 恢复 workspaceTitle from session name (task session name IS the workspace title)
            if (ui.name() != null && !ui.name().isBlank()) {
                loop.setWorkspaceTitle(ui.name());
            }
        }

        // 若非 conversation session 且 sessionEntryPrompt 尚未注入，重启后恢复（Layer 2）
        if (ui != null && !ui.isConversation() && loop.getSessionEntryPrompt() == null) {
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

                loop.chat(finalMessage, finalUiHints, event -> {
                    ChatEvent toSend = event;
                    try {
                        if (event.type() == ChatEvent.Type.TEXT) {
                            // TEXT 是后端持久化信号，只存历史，不下发前端
                            messageHistory.save(finalAgentSessionId, currentUiId[0], "assistant", event.content());
                            return;   // ← 不 forward 给 SSE

                        } else if (event.type() == ChatEvent.Type.SESSION) {
                            // 拦截 SESSION 事件：创建新 UiSession，记录其 id 供后续 CANVAS 更新用
                            toSend = enrichSessionEvent(event);
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
            String agentId          = n.path("agent_session_id").asText("main");
            String name             = n.path("name").asText("新任务");
            String type             = n.path("type").asText("task");
            String welcomeMsg       = n.path("welcome_message").asText("");
            String canvasSessionId  = n.path("canvas_session_id").asText("");

            // find-or-create：若已有对应 canvas_session_id 的 UiSession，直接复用
            UiSession existing = canvasSessionId.isBlank()
                    ? null : uiStore.findByCanvasSessionId(canvasSessionId);
            UiSession ui = (existing != null) ? existing : uiStore.create(type, name, agentId);

            String payload = "{\"ui_session_id\":\"" + escapeJson(ui.id())
                           + "\",\"name\":\""         + escapeJson(name)
                           + "\",\"type\":\""         + escapeJson(type)
                           + (welcomeMsg.isBlank() ? "" :
                                 "\",\"welcome_message\":\"" + escapeJson(welcomeMsg))
                           + "\"}";
            return ChatEvent.session(payload);
        } catch (Exception ignored) {
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

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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
                Map<String, Object> body = Map.of(
                    "args", Map.of(
                        "workspace_id",    workspaceId,
                        "workspace_title", workspaceTitle != null ? workspaceTitle : workspaceId
                    ),
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
