package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorldoneSystemSkillsControllerTest {

    @Test
    void appListView_returnsHtmlWidgetForMainSession() {
        WorldoneSystemSkillsController controller = new WorldoneSystemSkillsController();
        controller.registry = new AppRegistry() {
            @Override
            public List<Map<String, Object>> buildAppsManifests() {
                return List.of(
                    Map.of(
                        "app_id", "world",
                        "app_name", "世界设计",
                        "app_description", "定义本体结构",
                        "app_color", "#7c6ff7",
                        "is_active", true,
                        "load_ok", true,
                        "main_widget_type", "entity-graph"
                    ),
                    Map.of(
                        "app_id", "memory-one",
                        "app_name", "记忆管理",
                        "app_description", "管理长期记忆",
                        "app_color", "#60a5fa",
                        "is_active", true,
                        "load_ok", true,
                        "main_widget_type", "memory-manager"
                    )
                );
            }
        };

        Map<String, Object> body = controller.appListView(Map.of()).getBody();

        assertThat(body).containsEntry("ok", true);
        assertThat(body).containsKey("html_widget");
        @SuppressWarnings("unchecked")
        Map<String, Object> widget = (Map<String, Object>) body.get("html_widget");
        assertThat(widget)
            .containsEntry("title", "应用列表")
            .containsEntry("widget_type", "sys.app-list")
            .containsKey("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) widget.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) data.get("apps");
        assertThat(apps)
            .extracting(a -> a.get("app_name"))
            .contains("世界设计", "记忆管理");
        assertThat(body).doesNotContainKey("text");
    }
}
