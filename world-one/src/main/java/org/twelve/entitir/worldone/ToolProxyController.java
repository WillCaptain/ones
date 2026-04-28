package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tool & 通用 App Proxy。
 *
 * <h2>解耦协议</h2>
 * <ul>
 *   <li>{@code POST /api/proxy/tools/{toolName}} —— 工具调用代理（保留）。
 *       根据 {@link AppRegistry#findAppForTool(String)} 路由到对应 app。</li>
 *   <li>{@code (GET|POST|PATCH|DELETE) /api/proxy/app/{appId}/**} —— 通用 HTTP 代理。
 *       任何前端/扩展可以经此调用 {@code <baseUrl>/<剩余路径>}，
 *       无需 host 为每个领域端点写 if/else。SSE 自动按 Accept 头切到流模式。</li>
 * </ul>
 *
 * <p><b>禁用</b>：旧的 {@code /api/proxy/completions}、{@code /api/proxy/worlds/{id}/runtime-events}
 * 等具名领域端点已全部删除。前端改用 {@code /api/proxy/app/{appId}/api/...}。
 */
@RestController
@RequestMapping("/api/proxy")
public class ToolProxyController {

    @Autowired
    private AppRegistry registry;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @PostMapping("/tools/{toolName}")
    public ResponseEntity<?> proxyTool(
            @PathVariable("toolName") String toolName,
            @RequestBody Map<String, Object> body) {
        try {
            AppRegistration app = registry.findAppForTool(toolName);
            String url = app.toolUrl(toolName);
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
     * 通用代理：把 {@code /api/proxy/app/{appId}/foo/bar?x=1} 转发到
     * {@code <baseUrl-of-appId>/foo/bar?x=1}，透传方法、headers（除 Host/Content-Length）和 body。
     *
     * <p>若客户端 Accept 头包含 {@code text/event-stream}，自动切到 SSE 流式转发。
     */
    @RequestMapping(value = "/app/{appId}/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH,
                      RequestMethod.PUT, RequestMethod.DELETE})
    public Object proxyApp(@PathVariable("appId") String appId,
                           HttpServletRequest request,
                           @RequestBody(required = false) byte[] body) {
        AppRegistration app;
        try {
            app = findAppById(appId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        String prefix = "/api/proxy/app/" + appId;
        String reqUri = request.getRequestURI();
        String tail   = reqUri.startsWith(prefix) ? reqUri.substring(prefix.length()) : reqUri;
        if (tail.isEmpty()) tail = "/";
        String query  = request.getQueryString();
        String url    = app.baseUrl() + tail + (query == null ? "" : "?" + query);

        String accept = request.getHeader("Accept");
        boolean wantsSse = accept != null
                && accept.toLowerCase(Locale.ROOT).contains("text/event-stream");

        if (wantsSse) return proxySseStream(url);

        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30));
            String contentType = request.getContentType();
            if (contentType != null) b.header("Content-Type", contentType);
            HttpRequest.BodyPublisher publisher = (body == null || body.length == 0)
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            b.method(request.getMethod().toUpperCase(Locale.ROOT), publisher);

            HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            String respCt = resp.headers().firstValue("Content-Type").orElse(MediaType.APPLICATION_JSON_VALUE);
            return ResponseEntity.status(resp.statusCode())
                    .header("Content-Type", respCt)
                    .body(resp.body());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private SseEmitter proxySseStream(String url) {
        SseEmitter emitter = new SseEmitter(0L);
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
        return emitter;
    }

    private AppRegistration findAppById(String appId) {
        for (AppRegistration app : registry.apps()) {
            if (app.appId().equals(appId)) return app;
        }
        throw new IllegalArgumentException("Unknown app: " + appId);
    }

}
