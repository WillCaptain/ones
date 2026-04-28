package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Host 解耦协议中 {@code output_widget_rules} 的运行时行为：
 * <ul>
 *   <li>{@code force_canvas_when} 全部字段非空时返回 true</li>
 *   <li>缺字段或字段为空时返回 false</li>
 *   <li>规则缺失时返回 false 且 {@link AppRegistry#getDefaultWidget} 返回 null</li>
 *   <li>注册了 rules 的 skill 通过 {@link AppRegistry#getOutputWidgetRules} 可读出</li>
 * </ul>
 */
class OutputWidgetRulesTest {
    static {
        System.setProperty("ones.apps.root",
            System.getProperty("java.io.tmpdir") + "/ones-test-empty-" + System.nanoTime());
    }


    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void matchesForceCanvas_allFieldsPresent_returnsTrue() throws Exception {
        Map<String, Object> rules = Map.of(
            "force_canvas_when", List.of("graph", "session_id"),
            "default_widget",    "demo-widget"
        );
        JsonNode root = JSON.readTree("{\"graph\":{\"nodes\":[]},\"session_id\":\"s1\"}");
        assertThat(AppRegistry.matchesForceCanvas(root, rules)).isTrue();
    }

    @Test
    void matchesForceCanvas_blankStringField_returnsFalse() throws Exception {
        Map<String, Object> rules = Map.of(
            "force_canvas_when", List.of("graph", "session_id")
        );
        JsonNode root = JSON.readTree("{\"graph\":{\"x\":1},\"session_id\":\"\"}");
        assertThat(AppRegistry.matchesForceCanvas(root, rules)).isFalse();
    }

    @Test
    void matchesForceCanvas_missingField_returnsFalse() throws Exception {
        Map<String, Object> rules = Map.of("force_canvas_when", List.of("graph", "session_id"));
        JsonNode root = JSON.readTree("{\"graph\":{\"x\":1}}");
        assertThat(AppRegistry.matchesForceCanvas(root, rules)).isFalse();
    }

    @Test
    void matchesForceCanvas_emptyRules_returnsFalse() throws Exception {
        JsonNode root = JSON.readTree("{\"graph\":1}");
        assertThat(AppRegistry.matchesForceCanvas(root, Map.of())).isFalse();
        assertThat(AppRegistry.matchesForceCanvas(root, null)).isFalse();
    }

    @Test
    void registry_indexesOutputWidgetRulesAndDefaultWidget() {
        AppRegistry reg = new AppRegistry();
        Map<String, Object> rules = Map.of(
            "force_canvas_when", List.of("graph", "session_id"),
            "default_widget",    "demo-widget"
        );
        Map<String, Object> skill = Map.of(
            "name", "demo_skill",
            "description", "x",
            "parameters", Map.of(),
            "output_widget_rules", rules
        );
        reg.registerBuiltin("demo-app", "Demo", "http://localhost:0",
            "rules", List.of(skill), List.of());

        assertThat(reg.getOutputWidgetRules("demo_skill")).isEqualTo(rules);
        assertThat(reg.getDefaultWidget("demo_skill")).isEqualTo("demo-widget");
        assertThat(reg.getDefaultWidget("unknown_skill")).isNull();
    }
}
