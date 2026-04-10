package org.twelve.memoryone.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.twelve.memoryone.agent.LLMMemoryConsolidator;
import org.twelve.memoryone.tools.MemoryTools;

import java.util.*;

/**
 * POST /api/tools/{name} — memory-one 工具端点。
 *
 * <h2>请求体格式</h2>
 * <pre>
 * {
 *   "args": { ... },           // LLM 传入的参数
 *   "_context": {              // worldone 注入的会话上下文
 *     "userId": "default",
 *     "sessionId": "abc123",
 *     "agentId": "worldone"
 *   },
 *   "turn_messages": [...]     // 仅 memory_consolidate：worldone 注入的本轮消息
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/tools")
public class MemoryToolsController {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MemoryTools           tools;
    @Autowired private LLMMemoryConsolidator consolidator;

    @PostMapping("/memory_load")
    public ResponseEntity<Map<String, Object>> load(@RequestBody Map<String, Object> body) {
        return ok(tools.load(args(body), context(body)));
    }

    @PostMapping("/memory_consolidate")
    public ResponseEntity<Map<String, Object>> consolidate(@RequestBody Map<String, Object> body) {
        Map<String, Object> ctx = context(body);
        String userId      = ctxStr(ctx, "userId",      "default");
        String sessionId   = ctxStr(ctx, "sessionId",   null);
        String workspaceId = ctxStr(ctx, "workspaceId", null);
        String agentId     = ctxStr(ctx, "agentId",     "memory-one");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> turnMessages =
                body.get("turn_messages") instanceof List<?> tl
                        ? (List<Map<String, Object>>) tl : List.of();

        consolidator.consolidate(userId, agentId, sessionId, workspaceId, turnMessages, List.of());

        return ok(Map.of("ok", true, "message", "memory consolidation started (async)"));
    }

    @PostMapping("/memory_query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, Object> body) {
        return ok(tools.query(args(body), context(body)));
    }

    @PostMapping("/memory_create")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ok(tools.create(args(body), context(body)));
    }

    @PostMapping("/memory_update")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        return ok(tools.update(args(body), context(body)));
    }

    @PostMapping("/memory_supersede")
    public ResponseEntity<Map<String, Object>> supersede(@RequestBody Map<String, Object> body) {
        return ok(tools.supersede(args(body), context(body)));
    }

    @PostMapping("/memory_delete")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        return ok(tools.delete(args(body), context(body)));
    }

    @PostMapping("/memory_workspace_join")
    public ResponseEntity<Map<String, Object>> workspaceJoin(@RequestBody Map<String, Object> body) {
        return ok(tools.registerWorkspaceParticipation(args(body), context(body)));
    }

    @PostMapping("/memory_promote")
    public ResponseEntity<Map<String, Object>> promote(@RequestBody Map<String, Object> body) {
        return ok(tools.promote(args(body), context(body)));
    }

    @PostMapping("/memory_set_instruction")
    public ResponseEntity<Map<String, Object>> setInstruction(@RequestBody Map<String, Object> body) {
        return ok(tools.setInstruction(args(body), context(body)));
    }

    /**
     * memory_view：打开记忆管理面板——加载当前用户记忆，返回 graph.memories 供前端渲染。
     *
     * <p>支持 scope 过滤（前端 tab 切换时调用）：
     * <ul>
     *   <li>{@code scope=ALL}（默认）— 管理模式，返回用户所有活跃记忆</li>
     *   <li>{@code scope=GLOBAL}       — 只返回全局记忆</li>
     *   <li>{@code scope=WORKSPACE} + {@code workspace_id=X} — 只返回该工作区的记忆</li>
     *   <li>{@code scope=SESSION}   + {@code session_id=X}   — 只返回该会话的记忆</li>
     * </ul>
     */
    @PostMapping("/memory_view")
    public ResponseEntity<Map<String, Object>> view(@RequestBody Map<String, Object> body) {
        Map<String, Object> ctx  = context(body);
        Map<String, Object> args = args(body);

        String scopeParam  = ctxStr(args, "scope",        "ALL");
        String workspaceId = ctxStr(args, "workspace_id", null);
        String sessionId   = ctxStr(args, "session_id",   null);
        String keyword     = ctxStr(args, "keyword",      null);

        Map<String, Object> queryArgs = new LinkedHashMap<>();
        queryArgs.put("limit", 200);

        boolean allScopes = scopeParam == null || "ALL".equalsIgnoreCase(scopeParam);
        if (allScopes) {
            // Management mode: show all user-owned active memories (all scopes)
            queryArgs.put("for_manager", true);
        } else {
            // Scoped mode: regular query respecting scope visibility rules
            queryArgs.put("scope", scopeParam);
            if (workspaceId != null) queryArgs.put("workspace_id", workspaceId);
            if (sessionId   != null) queryArgs.put("session_id",   sessionId);
        }
        if (keyword != null) queryArgs.put("keyword", keyword);

        Map<String, Object> result = tools.query(queryArgs, ctx);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> memories = result.get("memories") instanceof List<?> ml
                ? (List<Map<String, Object>>) ml : List.of();

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("memories", memories);
        graph.put("session_name", "记忆管理");

        return ok(Map.of("ok", true, "graph", graph,
                "message", "Memory panel opened. Found " + memories.size() + " memories."));
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> args(Map<String, Object> body) {
        Object a = body.get("args");
        return a instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(Map<String, Object> body) {
        Object c = body.get("_context");
        return c instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String ctxStr(Map<String, Object> ctx, String key, String defaultVal) {
        Object v = ctx.get(key);
        return v instanceof String s ? s : defaultVal;
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> result) {
        return ResponseEntity.ok(result);
    }
}
