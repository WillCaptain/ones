package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GET /api/widgets — 聚合所有注册 app 的 widget 清单。
 *
 * <p>前端启动时调用此端点，获取所有 widget 的上传配置等能力声明，
 * 缓存到 _widgetManifests 供 canvas 模式按需使用。
 */
@RestController
@RequestMapping("/api")
public class WidgetsEndpoint {

    @Autowired
    private AppRegistry registry;

    @GetMapping("/widgets")
    public Map<String, Object> widgets() {
        List<Map<String, Object>> all = registry.apps().stream()
            .flatMap(a -> a.widgets().stream().map(w -> enrich(w, a)))
            .collect(Collectors.toList());
        return Map.of("widgets", all);
    }

    /**
     * Plan D enrichment: attach the owning AIPP's base URL so the host frontend
     * can resolve a widget's {@code render.url} (typically a relative path like
     * {@code /widgets/foo/foo.js}) to a full URL for {@code dynamic import}.
     *
     * <p>Performed at aggregation time so each AIPP doesn't need to know its
     * own external address — host already knows it from /api/registry/install.
     */
    private static Map<String, Object> enrich(Map<String, Object> widget, AppRegistration app) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(widget);
        out.put("app_base_url", app.baseUrl());
        return out;
    }
}
