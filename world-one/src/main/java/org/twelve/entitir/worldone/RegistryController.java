package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry 管理端点。
 *
 * <ul>
 *   <li>GET  /api/registry          — 列出所有已注册 app</li>
 *   <li>POST /api/registry/install  — 安装新 app（传入 appId + baseUrl）</li>
 *   <li>GET  /api/registry/tools    — 返回所有 app 聚合后的 tools</li>
 *   <li>GET  /api/registry/widgets  — 返回所有 app 聚合后的 widgets</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/registry")
public class RegistryController {

    @Autowired
    private AppRegistry registry;

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> apps = registry.apps().stream().map(app -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          app.appId());
            m.put("name",        app.name());
            m.put("base_url",    app.baseUrl());
            m.put("tool_count", app.tools().size());
            m.put("widget_count",app.widgets().size());
            return m;
        }).collect(Collectors.toList());
        return Map.of("apps", apps, "total", apps.size());
    }

    @PostMapping("/install")
    public Map<String, Object> install(@RequestBody Map<String, String> body) {
        String appId   = body.get("app_id");
        String baseUrl = body.get("base_url");
        if (appId == null || appId.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            return Map.of("success", false, "error", "app_id and base_url are required");
        }
        try {
            registry.install(appId, baseUrl);
            return Map.of("success", true, "message", "App " + appId + " installed successfully");
        } catch (Exception e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @GetMapping("/tools")
    public Map<String, Object> tools() {
        return Map.of(
            "tools",         registry.allTools(),
            "system_prompt", registry.aggregatedSystemPrompt()
        );
    }

    @GetMapping("/widgets")
    public Map<String, Object> widgets() {
        List<Map<String, Object>> all = registry.apps().stream()
            .flatMap(a -> a.widgets().stream())
            .collect(Collectors.toList());
        return Map.of("widgets", all);
    }
}
