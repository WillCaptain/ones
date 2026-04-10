package org.twelve.memoryone.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/app — 公布 memory-one 的应用身份信息（AIPP App Manifest）。
 *
 * <p>World-one 在注册此 app 时读取此端点，用于 Apps 启动面板展示：
 * 图标、名称、描述、主题色。
 */
@RestController
@RequestMapping("/api")
public class MemoryAppController {

    /** memory-one SVG 图标（大脑/记忆主题） */
    private static final String APP_ICON = """
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2a5 5 0 0 1 5 5c0 1.5-.6 2.8-1.5 3.8A5 5 0 0 1 17 15a5 5 0 0 1-10 0 5 5 0 0 1 1.5-4.2A5 5 0 0 1 7 7a5 5 0 0 1 5-5z"/>
              <path d="M12 7v5l3 2"/>
            </svg>""";

    @GetMapping("/app")
    public Map<String, Object> appManifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app_id",          "memory-one");
        m.put("app_name",        "记忆管理");
        m.put("app_icon",        APP_ICON.strip());
        m.put("app_description", "管理 AI Agent 的长期记忆（事实、目标、事件、关系）");
        m.put("app_color",       "#7c6ff7");
        m.put("is_active",       true);
        m.put("version",         "1.0");
        return m;
    }
}
