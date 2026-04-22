package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/proxy/tools/{toolName}
 *
 * <p>直接将工具调用转发到对应 app，<b>不经过 LLM</b>。
 * 用于前端 P2 功能（如内联字段编辑）直接触发工具执行，结果即时返回。
 *
 * <pre>
 * 请求体：{ "args": { "session_id": "...", ...其他参数 } }
 * 响应体：工具返回的 JSON（含 canvas 字段时，前端可解析更新图）
 * </pre>
 */
@RestController
@RequestMapping("/api/proxy")
public class ToolProxyController {

    @Autowired
    private AppRegistry registry;

    private static final ObjectMapper JSON    = new ObjectMapper();
    private final        HttpClient   http    = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @PostMapping("/tools/{toolName}")
    public ResponseEntity<?> proxyTool(
            @PathVariable("toolName") String toolName,
            @RequestBody Map<String, Object> body) {
        try {
            AppRegistration app = registry.findAppForTool(toolName);
            String url     = app.toolUrl(toolName);
            Map<String, Object> outBody = new LinkedHashMap<>(body == null ? Map.of() : body);
            @SuppressWarnings("unchecked")
            Map<String, Object> args = outBody.get("args") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            outBody.put("args", registry.injectEnvVars(app.appId(), args));
            String reqBody = JSON.writeValueAsString(outBody);

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(resp.body());

            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/proxy/completions — 转发到 world-entitir /api/completions（Outline 自动补全）。
     * POST /api/proxy/validate   — 转发到 world-entitir /api/validate（Outline 诊断）。
     *
     * <p>通过已注册 app 的 baseUrl 定位 world-entitir，无需硬编码端口。
     */
    @PostMapping("/completions")
    public ResponseEntity<?> proxyCompletions(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/completions", body);
    }

    @PostMapping("/validate")
    public ResponseEntity<?> proxyValidate(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/validate", body);
    }

    @PostMapping("/schema-validate")
    public ResponseEntity<?> proxySchemaValidate(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/schema-validate", body);
    }

    @PostMapping("/schema-completions")
    public ResponseEntity<?> proxySchemaCompletions(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/schema-completions", body);
    }

    @PostMapping("/schema-hover")
    public ResponseEntity<?> proxySchemaHover(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/schema-hover", body);
    }

    @PostMapping("/code-hover")
    public ResponseEntity<?> proxyCodeHover(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/code-hover", body);
    }

    @PostMapping("/code-members")
    public ResponseEntity<?> proxyCodeMembers(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/code-members", body);
    }

    @PostMapping("/infer-action-type")
    public ResponseEntity<?> proxyInferActionType(@RequestBody Map<String, Object> body) {
        return forwardToWorldEntitir("/api/infer-action-type", body);
    }

    @GetMapping("/schema-types")
    public ResponseEntity<?> proxySchemaTypes(
            @RequestParam(name = "session_id") String sessionId) {
        try {
            AppRegistration app = registry.findAppForTool("world_register_action");
            String url = app.baseUrl() + "/api/schema-types?session_id="
                    + java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(resp.body());
            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @org.springframework.web.bind.annotation.GetMapping("/session-outline")
    public ResponseEntity<?> proxySessionOutline(
            @org.springframework.web.bind.annotation.RequestParam(name = "session_id") String sessionId) {
        try {
            AppRegistration app = registry.findAppForTool("world_register_action");
            String url = app.baseUrl() + "/api/session-outline?session_id=" + sessionId;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(resp.body());
            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/worlds/{worldId}/runtime-events")
    public ResponseEntity<?> proxyRuntimeEvents(
            @PathVariable("worldId") String worldId,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "need_user_action", required = false) String needUserAction
    ) {
        try {
            AppRegistration app = registry.findAppForTool("world_register_action");
            Map<String, String> query = new LinkedHashMap<>();
            putIfNotBlank(query, "env", env);
            putIfNotBlank(query, "cursor", cursor);
            putIfNotBlank(query, "limit", limit);
            putIfNotBlank(query, "event_type", eventType);
            putIfNotBlank(query, "need_user_action", needUserAction);
            String url = buildRuntimeEventsUrl(app.baseUrl(), worldId, query, false);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(resp.body());
            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/worlds/{worldId}/runtime-events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter proxyRuntimeEventsStream(
            @PathVariable("worldId") String worldId,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "need_user_action", required = false) String needUserAction
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            AppRegistration app = registry.findAppForTool("world_register_action");
            Map<String, String> query = new LinkedHashMap<>();
            putIfNotBlank(query, "env", env);
            putIfNotBlank(query, "cursor", cursor);
            putIfNotBlank(query, "event_type", eventType);
            putIfNotBlank(query, "need_user_action", needUserAction);
            String url = buildRuntimeEventsUrl(app.baseUrl(), worldId, query, true);

            Thread.startVirtualThread(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .header("Accept", "text/event-stream")
                            .timeout(Duration.ofMinutes(30))
                            .GET()
                            .build();
                    HttpResponse<java.io.InputStream> resp =
                            http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(
                            resp.body(), StandardCharsets.UTF_8))) {
                        String line;
                        String eventId = null;
                        String eventName = null;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("id:")) {
                                eventId = line.substring(3).trim();
                            } else if (line.startsWith("event:")) {
                                eventName = line.substring(6).trim();
                            } else if (line.startsWith("data:")) {
                                SseEmitter.SseEventBuilder b = SseEmitter.event()
                                        .data(line.substring(5).trim());
                                if (eventId != null && !eventId.isBlank()) b.id(eventId);
                                if (eventName != null && !eventName.isBlank()) b.name(eventName);
                                emitter.send(b);
                            } else if (line.isBlank()) {
                                eventId = null;
                                eventName = null;
                            }
                        }
                    }
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            });
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** PATCH /api/proxy/worlds/{sessionId}/rename — 转发到 world-entitir 的世界重命名接口。 */
    @PatchMapping("/worlds/{sessionId}/rename")
    public ResponseEntity<?> renameWorld(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Object> body) {
        try {
            AppRegistration app = registry.findAppForTool("world_register_action");
            String url = app.baseUrl() + "/api/worlds/"
                    + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/rename";
            String reqBody = JSON.writeValueAsString(body == null ? Map.of() : body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(reqBody))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(resp.body());
            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> forwardToWorldEntitir(String path, Map<String, Object> body) {
        try {
            AppRegistration app = registry.findAppForTool("world_register_action");
            String url = app.baseUrl() + path;
            String reqBody = JSON.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode result = JSON.readTree(resp.body());
            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    static String buildRuntimeEventsUrl(String baseUrl, String worldId, Map<String, String> query, boolean stream) {
        StringBuilder sb = new StringBuilder(baseUrl)
                .append("/api/worlds/")
                .append(URLEncoder.encode(worldId, StandardCharsets.UTF_8))
                .append("/runtime-events");
        if (stream) sb.append("/stream");
        if (query != null && !query.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isBlank()) continue;
                sb.append(first ? "?" : "&");
                first = false;
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                  .append("=")
                  .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static void putIfNotBlank(Map<String, String> query, String key, String value) {
        if (value != null && !value.isBlank()) query.put(key, value);
    }
}
