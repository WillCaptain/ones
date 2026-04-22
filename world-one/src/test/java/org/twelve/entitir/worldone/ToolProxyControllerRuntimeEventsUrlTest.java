package org.twelve.entitir.worldone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolProxyController runtime-events URL")
class ToolProxyControllerRuntimeEventsUrlTest {

    @Test
    @DisplayName("列表 URL：包含编码后的 worldId 与 query 参数")
    void buildsRuntimeEventsListUrl() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("env", "production");
        q.put("cursor", "1744790400000:evt-001");
        q.put("event_type", "decision");
        q.put("need_user_action", "true");
        String url = ToolProxyController.buildRuntimeEventsUrl(
                "http://localhost:8093", "hr world/1", q, false);
        assertThat(url).contains("/api/worlds/hr+world%2F1/runtime-events");
        assertThat(url).contains("env=production");
        assertThat(url).contains("cursor=1744790400000%3Aevt-001");
        assertThat(url).contains("event_type=decision");
        assertThat(url).contains("need_user_action=true");
    }

    @Test
    @DisplayName("流式 URL：走 /runtime-events/stream，忽略空参数")
    void buildsRuntimeEventsStreamUrl() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("env", "draft");
        q.put("cursor", "");
        String url = ToolProxyController.buildRuntimeEventsUrl(
                "http://localhost:8093", "hr", q, true);
        assertThat(url).isEqualTo("http://localhost:8093/api/worlds/hr/runtime-events/stream?env=draft");
    }
}

