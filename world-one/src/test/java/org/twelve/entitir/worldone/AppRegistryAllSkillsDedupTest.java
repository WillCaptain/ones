package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link AppRegistry#allSkillsAsTools()} 对跨 app / 跨 scope 同名工具做去重，
 * 且过滤掉 {@code visibility} 不含 {@code "llm"} 的条目，避免触发 LLM 的
 * "Tool names must be unique" 400 错误。
 *
 * <p>语义优先级：{@code scope.level=app} 的副本优先于 {@code widget} 副本；
 * 同级别下 {@code visible_when=always} 优先。
 */
class AppRegistryAllSkillsDedupTest {

    @Test
    void deduplicatesSameNameAndFiltersNonLlmVisibility() throws Exception {
        AppRegistry reg = new AppRegistry();

        // 构造一个 app 含 4 份 memory_query —— 模拟 memory-one 现状：
        //   1) app 级 always, visibility=[llm,ui]           ← 应被保留
        //   2) widget 级 canvas_open, visibility=[llm,ui]   ← 应被去重丢弃
        //   3) app 级 always, visibility=[host]             ← visibility 不含 llm，丢弃
        //   4) widget 级 always, visibility=[ui]            ← visibility 不含 llm，丢弃
        Map<String, Object> appLevelLlm = new LinkedHashMap<>();
        appLevelLlm.put("name", "memory_query");
        appLevelLlm.put("description", "app-level");
        appLevelLlm.put("visibility", List.of("llm", "ui"));
        appLevelLlm.put("scope", Map.of("level", "app", "owner_app", "memory-one", "visible_when", "always"));

        Map<String, Object> widgetLevelLlm = new LinkedHashMap<>();
        widgetLevelLlm.put("name", "memory_query");
        widgetLevelLlm.put("description", "widget-level");
        widgetLevelLlm.put("visibility", List.of("llm", "ui"));
        widgetLevelLlm.put("scope", Map.of("level", "widget", "owner_app", "memory-one",
                                            "owner_widget", "memory-manager", "visible_when", "canvas_open"));

        Map<String, Object> appLevelHost = new LinkedHashMap<>();
        appLevelHost.put("name", "memory_load");
        appLevelHost.put("description", "host-only");
        appLevelHost.put("visibility", List.of("host"));
        appLevelHost.put("scope", Map.of("level", "app", "owner_app", "memory-one", "visible_when", "always"));

        Map<String, Object> widgetLevelUi = new LinkedHashMap<>();
        widgetLevelUi.put("name", "memory_load");
        widgetLevelUi.put("description", "ui-only");
        widgetLevelUi.put("visibility", List.of("ui"));
        widgetLevelUi.put("scope", Map.of("level", "widget", "owner_app", "memory-one",
                                           "owner_widget", "memory-manager", "visible_when", "always"));

        // 额外：跨 app 同名 → 按优先级与先后顺序保留其一
        Map<String, Object> otherApp = new LinkedHashMap<>();
        otherApp.put("name", "world_list");
        otherApp.put("description", "world list tool");
        otherApp.put("visibility", List.of("llm", "ui"));
        otherApp.put("scope", Map.of("level", "app", "owner_app", "world", "visible_when", "always"));

        AppRegistration memoryOne = new AppRegistration(
            "memory-one", "memory-one", "http://x", "",
            List.of(),  // promptContributions
            List.of(appLevelLlm, widgetLevelLlm, appLevelHost, widgetLevelUi),  // skills
            List.of()   // widgets
        );
        AppRegistration world = new AppRegistration(
            "world", "world", "http://y", "",
            List.of(), List.of(otherApp), List.of()
        );
        injectRegistry(reg, Map.of("memory-one", memoryOne, "world", world));

        List<Map<String, Object>> tools = reg.allSkillsAsTools();

        // 1) LLM 看到的 tool 名称必须唯一
        List<String> names = tools.stream().map(t -> t.get("name").toString()).toList();
        assertThat(names).doesNotHaveDuplicates();

        // 2) memory_query 保留了 app 级副本
        Map<String, Object> kept = tools.stream()
                .filter(t -> "memory_query".equals(t.get("name")))
                .findFirst().orElseThrow();
        assertThat(((Map<?, ?>) kept.get("scope")).get("level")).isEqualTo("app");
        assertThat(kept.get("description")).isEqualTo("app-level");

        // 3) memory_load 两份都被过滤（host-only + ui-only）
        assertThat(names).doesNotContain("memory_load");

        // 4) 其他 app 的 tool 正常保留
        assertThat(names).contains("world_list");
    }

    @SuppressWarnings("unchecked")
    private static void injectRegistry(AppRegistry target, Map<String, AppRegistration> apps) throws Exception {
        Field f = AppRegistry.class.getDeclaredField("registry");
        f.setAccessible(true);
        Map<String, AppRegistration> m = (Map<String, AppRegistration>) f.get(target);
        m.clear();
        m.putAll(apps);
    }
}
