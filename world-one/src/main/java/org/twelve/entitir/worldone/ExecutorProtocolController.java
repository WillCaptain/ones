package org.twelve.entitir.worldone;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side executor protocol endpoints:
 * - discover registrations
 * - route one runtime event to matched executor
 */
@RestController
@RequestMapping("/api/executors")
public class ExecutorProtocolController {

    private final ExecutorRoutingService routingService;

    public ExecutorProtocolController(ExecutorRoutingService routingService) {
        this.routingService = routingService;
    }

    @GetMapping
    public Map<String, Object> listExecutors() {
        List<Map<String, Object>> regs = routingService.discoverRegistrations();
        return Map.of("executors", regs, "total", regs.size());
    }

    @PostMapping("/route")
    public ResponseEntity<Map<String, Object>> routeRuntimeEvent(@RequestBody Map<String, Object> body) {
        String worldId = body.get("world_id") == null ? "" : String.valueOf(body.get("world_id"));
        String env = normalizeEnv(body.get("env"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = body.get("event") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        if (worldId.isBlank() || event.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "invalid_request",
                    "error", "world_id and event are required"
            ));
        }
        Map<String, Object> result = routingService.routeDecisionEvent(worldId, env, event);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("world_id", worldId);
        out.put("env", env);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/approval")
    public ResponseEntity<Map<String, Object>> submitApproval(@RequestBody Map<String, Object> body) {
        String worldId = body.get("world_id") == null ? "" : String.valueOf(body.get("world_id"));
        String env = normalizeEnv(body.get("env"));
        String result = body.get("result") == null ? "" : String.valueOf(body.get("result"));
        String approvedBy = body.get("approved_by") == null ? "user" : String.valueOf(body.get("approved_by"));
        String comment = body.get("comment") == null ? "" : String.valueOf(body.get("comment"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = body.get("event") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        if (worldId.isBlank() || event.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "invalid_request",
                    "error", "world_id and event are required"
            ));
        }
        Map<String, Object> out = new LinkedHashMap<>(
                routingService.processApproval(worldId, env, event, result, approvedBy, comment)
        );
        out.put("world_id", worldId);
        out.put("env", env);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/resume-arg")
    public ResponseEntity<Map<String, Object>> resumeArg(@RequestBody Map<String, Object> body) {
        String worldId = body.get("world_id") == null ? "" : String.valueOf(body.get("world_id"));
        String env = normalizeEnv(body.get("env"));
        String registrationId = body.get("registration_id") == null ? "" : String.valueOf(body.get("registration_id"));
        String appId = body.get("app_id") == null ? "" : String.valueOf(body.get("app_id"));
        String suspensionId = body.get("suspension_id") == null ? "" : String.valueOf(body.get("suspension_id"));
        String flowId = body.get("flow_id") == null ? "" : String.valueOf(body.get("flow_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> submittedParams = body.get("submitted_params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        if (worldId.isBlank() || suspensionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "invalid_request",
                    "error", "world_id and suspension_id are required"
            ));
        }
        Map<String, Object> out = new LinkedHashMap<>(
                routingService.resumeArgFlow(worldId, env, registrationId, appId, suspensionId, flowId, submittedParams)
        );
        out.put("world_id", worldId);
        out.put("env", env);
        return ResponseEntity.ok(out);
    }

    private static String normalizeEnv(Object rawEnv) {
        if (rawEnv == null) return "production";
        String v = String.valueOf(rawEnv).trim().toLowerCase();
        return switch (v) {
            case "draft", "staging", "production" -> v;
            default -> "production";
        };
    }
}

