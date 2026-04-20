package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 验证：{@link AppRegistry#indexWidgetScopedFromTools} 能够
 * 从 /api/tools 返回的 skills 列表中识别 {@code scope.level=widget} 的工具，
 * 按 {@code visibility} 分派到 {@code toolIndex}（UI）和 {@code widgetCanvasToolsIndex}（LLM canvas）。
 */
class AppRegistryWidgetScopedTest {

    @Test
    @SuppressWarnings("unchecked")
    void widgetScopedTools_areIndexedByVisibilityAndOwnerWidget() throws Exception {
        AppRegistry reg = new AppRegistry();
        AppRegistration app = new AppRegistration(
            "world", "world", "http://x", "", List.of(), List.of(), List.of()
        );

        Map<String, Object> canvasOnly = Map.of(
            "name", "world_modify_decision",
            "description", "modify decision",
            "parameters", Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            "visibility", List.of("llm"),
            "scope", Map.of("level", "widget", "owner_app", "world",
                            "owner_widget", "entity-graph", "visible_when", "canvas_open")
        );
        Map<String, Object> uiOnly = Map.of(
            "name", "world_validate",
            "description", "internal validate",
            "parameters", Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            "visibility", List.of("ui"),
            "scope", Map.of("level", "widget", "owner_app", "world",
                            "owner_widget", "entity-graph", "visible_when", "always")
        );
        Map<String, Object> both = Map.of(
            "name", "world_add_definition",
            "description", "add def",
            "parameters", Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            "visibility", List.of("llm", "ui"),
            "scope", Map.of("level", "widget", "owner_app", "world",
                            "owner_widget", "entity-graph", "visible_when", "canvas_open")
        );
        Map<String, Object> appLevel = Map.of(
            "name", "world_design",
            "description", "app level",
            "parameters", Map.of("type", "object", "properties", Map.of(), "required", List.of()),
            "visibility", List.of("llm", "ui"),
            "scope", Map.of("level", "app", "owner_app", "world", "visible_when", "always")
        );

        List<Map<String, Object>> skills = new ArrayList<>();
        skills.add(canvasOnly);
        skills.add(uiOnly);
        skills.add(both);
        skills.add(appLevel);

        Method m = AppRegistry.class.getDeclaredMethod(
            "indexWidgetScopedFromTools", List.class, AppRegistration.class);
        m.setAccessible(true);
        Set<String> preloaded = (Set<String>) m.invoke(reg, skills, app);

        assertThat(preloaded).containsExactly("entity-graph");

        List<Map<String, Object>> canvasTools = reg.getCanvasTools("entity-graph");
        List<String> canvasNames = canvasTools.stream()
            .map(t -> t.get("name").toString()).toList();
        assertThat(canvasNames).containsExactlyInAnyOrder(
            "world_modify_decision", "world_add_definition");

        // canvas 工具不应保留 visibility / scope 字段（去壳后交给 LLM）
        for (Map<String, Object> t : canvasTools) {
            assertThat(t).doesNotContainKeys("visibility", "scope");
        }

        // UI 可见 → toolIndex（反射读取 private 字段）
        java.lang.reflect.Field f = AppRegistry.class.getDeclaredField("toolIndex");
        f.setAccessible(true);
        Map<String, AppRegistration> toolIndex = (Map<String, AppRegistration>) f.get(reg);
        assertThat(toolIndex).containsKeys("world_validate", "world_add_definition");
        assertThat(toolIndex).doesNotContainKey("world_modify_decision");
    }
}
