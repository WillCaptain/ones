package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * World One 系统内置 skill 执行端点。
 *
 * <h2>端点</h2>
 * <ul>
 *   <li>{@code POST /api/tools/app_list_view} — skill 执行，返回 html_widget JSON</li>
 *   <li>{@code GET  /api/system/widgets/app-list} — 完整 HTML 页面，供 #apps-panel 内的 iframe 加载</li>
 * </ul>
 *
 * <p>两个端点共用同一 HTML 模板，区别仅在于包装方式：
 * POST 路径将 HTML 包装在 {@code html_widget} JSON 内（供 GenericAgentLoop extractEvents 处理），
 * GET 路径直接返回 HTML 文本（供 iframe src 使用）。
 *
 * <p>App 卡片点击通过 {@code window.parent.postMessage(\{type:'openApp', appId\}, '*')} 通知宿主，
 * 宿主收到后执行与原来 #apps-panel 点击完全一致的逻辑（{@code openAppDirect}）。
 */
@RestController
public class WorldoneSystemSkillsController {

    @Autowired AppRegistry registry;

    // ── Skill 执行端点（LLM → GenericAgentLoop → 此处）──────────────────────

    /**
     * POST /api/tools/app_list_view
     *
     * <p>参数：{@code args.query}（可选），按名称或描述过滤应用列表。
     * 返回 {@code \{html_widget: \{html, height\}\}} 供 extractEvents 处理。
     */
    @PostMapping("/api/tools/app_list_view")
    public ResponseEntity<Map<String, Object>> appListView(
            @RequestBody(required = false) Map<String, Object> body) {

        String query = extractQuery(body);
        List<Map<String, Object>> apps = filterApps(registry.buildAppsManifests(), query);

        String html   = buildHtmlPage(apps, query);
        String height = computeHeight(apps.size());

        return ResponseEntity.ok(Map.of(
            "html_widget", Map.of("html", html, "height", height, "title", "应用列表")
        ));
    }

    // ── Panel iframe 端点（#apps-panel 加载）────────────────────────────────

    /**
     * GET /api/system/widgets/app-list
     *
     * <p>返回完整 HTML 页面，在 #apps-panel 的 iframe 中加载。
     * 可选 {@code ?query=} 参数用于过滤（panel 场景一般不传，展示全部）。
     */
    @GetMapping(value = "/api/system/widgets/app-list", produces = MediaType.TEXT_HTML_VALUE)
    public String appListPage(
            @RequestParam(name = "query", required = false, defaultValue = "") String query) {

        List<Map<String, Object>> apps = filterApps(registry.buildAppsManifests(), query.trim());
        return buildHtmlPage(apps, query.trim());
    }

    // ── private helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractQuery(Map<String, Object> body) {
        if (body == null) return "";
        Object args = body.get("args");
        if (args instanceof Map<?, ?> m) {
            Object q = ((Map<String, Object>) m).get("query");
            if (q instanceof String s) return s.trim();
        }
        return "";
    }

    private List<Map<String, Object>> filterApps(List<Map<String, Object>> apps, String query) {
        // 无 main_widget_type 的 app 不出现在列表（host 系统、纯工具 app 等）
        var visible = apps.stream()
            .filter(a -> a.get("main_widget_type") != null)
            .toList();
        if (query == null || query.isBlank()) return visible;
        String lq = query.toLowerCase();
        return visible.stream()
            .filter(a -> {
                String name = str(a.getOrDefault("app_name", "")).toLowerCase();
                String desc = str(a.getOrDefault("app_description", "")).toLowerCase();
                return name.contains(lq) || desc.contains(lq);
            })
            .toList();
    }

    private String computeHeight(int count) {
        // 每张卡片约 58px，最小 100px，最大 440px（大约 7 张可见，超出内滚）
        int px = Math.max(100, Math.min(440, count * 58 + 8));
        return px + "px";
    }

    /** 构建完整 HTML 页面（srcdoc 或直接 src 均可使用）。 */
    private String buildHtmlPage(List<Map<String, Object>> apps, String query) {
        StringBuilder cards = new StringBuilder();

        if (apps.isEmpty()) {
            String msg = (query == null || query.isBlank())
                ? "暂无已注册应用"
                : "没有匹配「" + escHtml(query) + "」的应用";
            cards.append("<div class=\"empty\">").append(msg).append("</div>");
        } else {
            for (Map<String, Object> app : apps) {
                boolean active    = !Boolean.FALSE.equals(app.get("is_active"));
                boolean clickable = active && app.get("main_widget_type") != null;

                String appId   = str(app.getOrDefault("app_id",   ""));
                String name    = str(app.getOrDefault("app_name",  appId));
                String desc    = str(app.getOrDefault("app_description", ""));
                String color   = str(app.getOrDefault("app_color",  "#6b7a9e"));
                String rawIcon = str(app.getOrDefault("app_icon",   ""));

                String iconHtml = rawIcon.isEmpty()
                    ? defaultIconSvg(color)
                    : "<div class=\"app-icon\" style=\"color:" + escHtml(color) + "\">"
                        + rawIcon + "</div>";

                String cardClass = "app-card" + (active ? "" : " inactive");
                String onclick   = clickable
                    ? " onclick=\"openApp('" + escJs(appId) + "')\""
                    : "";

                cards.append("<div class=\"").append(cardClass).append("\"").append(onclick).append(">")
                     .append(iconHtml)
                     .append("<div class=\"app-info\">")
                     .append("<div class=\"app-name\">").append(escHtml(name)).append("</div>");
                if (!desc.isBlank()) {
                    cards.append("<div class=\"app-desc\">").append(escHtml(desc)).append("</div>");
                }
                cards.append("</div></div>");
            }
        }

        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"><style>
            *{margin:0;padding:0;box-sizing:border-box}
            html,body{height:100%}
            body{background:#13151f;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
                 color:#d0d8f0;overflow-y:auto}
            .app-card{display:flex;align-items:center;gap:10px;padding:9px 14px;cursor:pointer;
                      border-bottom:1px solid #272b3e;transition:background .12s}
            .app-card:last-child{border-bottom:none}
            .app-card:hover{background:rgba(124,111,247,.14)}
            .app-card.inactive{opacity:.45;cursor:default;pointer-events:none}
            .app-icon{width:32px;height:32px;border-radius:7px;display:flex;align-items:center;
                      justify-content:center;flex-shrink:0;font-size:18px;background:rgba(255,255,255,.06)}
            .app-icon svg{width:18px;height:18px}
            .app-name{font-size:13px;font-weight:500;color:#d0d8f0;line-height:1.3}
            .app-desc{font-size:11px;color:#6b7a9e;margin-top:2px;line-height:1.3}
            .empty{padding:24px;text-align:center;color:#6b7a9e;font-size:12px}
            </style></head>
            <body>
            """ + cards + """
            <script>
            function openApp(appId) {
              window.parent.postMessage({ type: 'openApp', appId: appId }, '*');
            }
            (function() {
              function reportHeight() {
                window.parent.postMessage({ type: 'appListHeight', height: document.body.scrollHeight }, '*');
              }
              if (document.readyState === 'complete') reportHeight();
              else window.addEventListener('load', reportHeight);
            })();
            </script>
            </body></html>
            """;
    }

    private static String defaultIconSvg(String color) {
        return "<div class=\"app-icon\" style=\"color:" + escHtml(color) + "\">"
            + "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\">"
            + "<rect x=\"3\" y=\"3\" width=\"7\" height=\"7\" rx=\"1\"/>"
            + "<rect x=\"14\" y=\"3\" width=\"7\" height=\"7\" rx=\"1\"/>"
            + "<rect x=\"3\" y=\"14\" width=\"7\" height=\"7\" rx=\"1\"/>"
            + "<rect x=\"14\" y=\"14\" width=\"7\" height=\"7\" rx=\"1\"/>"
            + "</svg></div>";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
