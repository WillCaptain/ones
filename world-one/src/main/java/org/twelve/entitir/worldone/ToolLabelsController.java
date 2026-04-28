package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 暴露所有 AIPP skill 的 display label 字典，供前端动态查表替代硬编码 TOOL_LABELS。
 *
 * <p>来源：每个 skill manifest 的 {@code display_label_zh} 或 {@code display_name}。
 */
@RestController
@RequestMapping("/api/tool-labels")
public class ToolLabelsController {

    @Autowired
    private AppRegistry registry;

    @GetMapping
    public Map<String, String> labels() {
        return registry.getAllToolDisplayLabels();
    }
}
