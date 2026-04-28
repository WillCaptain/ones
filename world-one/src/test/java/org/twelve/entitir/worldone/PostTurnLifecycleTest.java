package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link AppRegistry#findSkillsByLifecycle} 命中 {@code lifecycle:"post_turn"}
 * 的 skill，并保留对旧 {@code background:true} 的向后兼容（用于平滑迁移期）。
 */
class PostTurnLifecycleTest {
    static {
        System.setProperty("ones.apps.root",
            System.getProperty("java.io.tmpdir") + "/ones-test-empty-" + System.nanoTime());
    }


    private static Map<String, Object> skill(String name, Object lifecycle, boolean background) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("description", "");
        m.put("parameters", Map.of());
        if (lifecycle != null) m.put("lifecycle", lifecycle);
        if (background)       m.put("background", true);
        return m;
    }

    private static List<String> hitNames(AppRegistry reg, String lifecycle) {
        var l = new java.util.ArrayList<String>();
        for (var e : reg.findSkillsByLifecycle(lifecycle)) {
            l.add(String.valueOf(e.getValue().get("name")));
        }
        return l;
    }

    @Test
    void findsExplicitPostTurnLifecycle() {
        AppRegistry reg = new AppRegistry();
        reg.registerBuiltin("alpha-test", "Alpha", "http://localhost:0", "",
            List.of(skill("alpha_consolidate_test", "post_turn", false)), List.of());
        reg.registerBuiltin("beta-test", "Beta", "http://localhost:0", "",
            List.of(skill("beta_demand_test", "on_demand", false)), List.of());

        assertThat(hitNames(reg, "post_turn")).contains("alpha_consolidate_test");
        assertThat(hitNames(reg, "post_turn")).doesNotContain("beta_demand_test");
    }

    @Test
    void backwardCompatBackgroundFlagCountsAsPostTurn() {
        AppRegistry reg = new AppRegistry();
        reg.registerBuiltin("legacy-test", "Legacy", "http://localhost:0", "",
            List.of(skill("legacy_bg_test", null, true)), List.of());
        assertThat(hitNames(reg, "post_turn")).contains("legacy_bg_test");
    }

    @Test
    void noLifecycleNoMatch() {
        AppRegistry reg = new AppRegistry();
        reg.registerBuiltin("plain-test", "Plain", "http://localhost:0", "",
            List.of(skill("plain_tool_test", null, false)), List.of());
        assertThat(hitNames(reg, "post_turn")).doesNotContain("plain_tool_test");
    }
}
