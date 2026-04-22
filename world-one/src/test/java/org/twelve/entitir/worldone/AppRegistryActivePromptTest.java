package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppRegistryActivePromptTest {

    @Test
    void aggregatedSystemPromptOnlyIncludesActiveApps() {
        AppRegistry registry = new AppRegistry();
        registry.registerBuiltin(
                "app-a",
                "App A",
                "http://localhost:1",
                "A rules",
                List.of(Map.of("name", "a_tool", "description", "a", "parameters", Map.of())),
                List.of()
        );
        registry.registerBuiltin(
                "app-b",
                "App B",
                "http://localhost:2",
                "B rules",
                List.of(Map.of("name", "b_tool", "description", "b", "parameters", Map.of())),
                List.of()
        );

        String prompt = registry.aggregatedSystemPrompt(Set.of("app-a"));

        assertThat(prompt).contains("A rules");
        assertThat(prompt).doesNotContain("B rules");
        assertThat(prompt).contains("你是 World One");
    }

    @Test
    void aggregatedPrePromptOnlyIncludesAapPreLayer() {
        AppRegistry registry = new AppRegistry();
        registry.registerBuiltin(
                "app-a",
                "App A",
                "http://localhost:1",
                "",
                List.of(
                        Map.of("id", "pre-1", "layer", "aap_pre", "priority", 10, "content", "PRE rules"),
                        Map.of("id", "post-1", "layer", "aap_post", "priority", 100, "content", "POST manual")
                ),
                List.of(Map.of("name", "a_tool", "description", "a", "parameters", Map.of())),
                List.of()
        );

        String pre = registry.aggregatedPrePrompt(Set.of("app-a"));

        assertThat(pre).contains("PRE rules");
        assertThat(pre).doesNotContain("POST manual");
    }
}
