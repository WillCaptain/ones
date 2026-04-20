package org.twelve.entitir.worldone.skills;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillRegistry#visibleSkills} 位置过滤逻辑的回归测试。
 *
 * <p>Anthropic Skills 风格下，位置过滤是主 LLM "召回 catalog" 的唯一服务端约束：
 * 必须保证 universal / app / widget / view 四层粒度都只在匹配的 UI 上下文里出现，
 * 否则 catalog 会在无关位置污染 system prompt。
 */
class SkillRegistryVisibilityTest {

    private static SkillDefinition def(String name, String level,
                                       String ownerWidget, String ownerView) {
        return new SkillDefinition(
                name, "app-test", level,
                ownerWidget, ownerView,
                "Use when ...",
                List.of("t"),
                "/api/skills/" + name + "/playbook");
    }

    /** 通过反射塞入 indexByApp（纯内存测试，不启动 Spring）。 */
    @SuppressWarnings("unchecked")
    private static SkillRegistry withSkills(List<SkillDefinition> skills) throws Exception {
        SkillRegistry r = new SkillRegistry();
        Field f = SkillRegistry.class.getDeclaredField("indexByApp");
        f.setAccessible(true);
        Map<String, List<SkillDefinition>> m = (Map<String, List<SkillDefinition>>) f.get(r);
        m.put("app-test", skills);
        return r;
    }

    @Test
    void universal_isVisibleEverywhere() throws Exception {
        SkillRegistry r = withSkills(List.of(def("u", "universal", null, null)));
        assertThat(r.visibleSkills(null, null, Set.of())).extracting(SkillDefinition::name)
                .containsExactly("u");
        assertThat(r.visibleSkills("entity-graph", null, Set.of())).extracting(SkillDefinition::name)
                .containsExactly("u");
        assertThat(r.visibleSkills("memory-manager", "RELATION", Set.of("app-test")))
                .extracting(SkillDefinition::name).containsExactly("u");
    }

    @Test
    void appLevel_visibleWhenActiveAppsEmptyOrContainsIt() throws Exception {
        SkillRegistry r = withSkills(List.of(def("a", "app", null, null)));
        assertThat(r.visibleSkills(null, null, Set.of())).extracting(SkillDefinition::name)
                .containsExactly("a");
        assertThat(r.visibleSkills(null, null, Set.of("app-test")))
                .extracting(SkillDefinition::name).containsExactly("a");
        assertThat(r.visibleSkills(null, null, Set.of("other-app"))).isEmpty();
    }

    @Test
    void widgetLevel_matchesOnlyOwnerWidget() throws Exception {
        SkillRegistry r = withSkills(List.of(def("w", "widget", "entity-graph", null)));
        assertThat(r.visibleSkills("entity-graph", null, Set.of())).extracting(SkillDefinition::name)
                .containsExactly("w");
        assertThat(r.visibleSkills("entity-graph", "DECISION", Set.of()))
                .extracting(SkillDefinition::name).containsExactly("w");
        assertThat(r.visibleSkills("memory-manager", null, Set.of())).isEmpty();
        assertThat(r.visibleSkills(null, null, Set.of())).isEmpty();
    }

    @Test
    void viewLevel_matchesOwnerWidgetAndView() throws Exception {
        SkillRegistry r = withSkills(List.of(def("v", "view", "memory-manager", "RELATION")));
        assertThat(r.visibleSkills("memory-manager", "RELATION", Set.of()))
                .extracting(SkillDefinition::name).containsExactly("v");
        assertThat(r.visibleSkills("memory-manager", "ENTITY", Set.of())).isEmpty();
        assertThat(r.visibleSkills("entity-graph", "RELATION", Set.of())).isEmpty();
        assertThat(r.visibleSkills("memory-manager", null, Set.of())).isEmpty();
    }
}
