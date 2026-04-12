package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
            String reqBody = JSON.writeValueAsString(body);  // 原样转发 {args:{...}}

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
}
