package org.twelve.memoryone.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.twelve.memoryone.tools.MemoryTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用事件入口（Host 解耦协议）。
 *
 * <p>worldone host 通过 {@link org.twelve.memoryone.api.MemorySkillsController#tools()}
 * 中声明的 {@code event_subscriptions} 选择本 app 作为 {@code workspace.changed} 事件
 * 的接收方，POST 到 {@code /api/events}。Payload 形如：
 * <pre>{"type":"workspace.changed","data":{"workspace_id","workspace_title","user_id"}}</pre>
 */
@RestController
@RequestMapping("/api/events")
public class MemoryEventsController {

    @Autowired private MemoryTools tools;

    @PostMapping
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> body) {
        if (body == null) body = Map.of();
        String type = String.valueOf(body.getOrDefault("type", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = body.get("data") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        if ("workspace.changed".equals(type)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("workspace_id",    data.get("workspace_id"));
            args.put("workspace_title", data.get("workspace_title"));
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("userId",      data.getOrDefault("user_id", "default"));
            ctx.put("workspaceId", data.get("workspace_id"));
            ctx.put("agentId",     "worldone");
            tools.registerWorkspaceParticipation(args, ctx);
            return ResponseEntity.ok(Map.of("status", "handled"));
        }
        return ResponseEntity.ok(Map.of("status", "ignored", "type", type));
    }
}
