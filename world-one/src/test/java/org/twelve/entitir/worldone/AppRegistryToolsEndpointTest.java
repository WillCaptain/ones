package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 验证：{@link AppRegistry} 读取 tool 列表时，
 * 既能解析 /api/tools 风格 payload（根字段 {@code tools}，每条 tool 带 {@code visibility/scope}），
 * 也能回退解析旧 /api/skills 风格 payload（根字段 {@code skills}），
 * 且两种路径下 tool 名称、字段透传、app_id 注入一致。
 *
 * <p>直接针对私有方法 {@code fetchSkills(String, JsonNode)} 做反射调用。
 */
class AppRegistryToolsEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fetchSkills_parsesNewToolsPayloadWithVisibilityScope() throws Exception {
        String body = """
            {
              "app": "world",
              "version": "1.0",
              "tools": [
                {
                  "name": "world_design",
                  "description": "打开本体设计",
                  "parameters": {"type":"object","properties":{},"required":[]},
                  "visibility": ["llm","ui"],
                  "scope": {"level":"app","owner_app":"world","visible_when":"always"}
                },
                {
                  "name": "memory_consolidate",
                  "description": "整合本轮",
                  "parameters": {"type":"object","properties":{},"required":[]},
                  "background": true,
                  "visibility": ["host"],
                  "scope": {"level":"app","owner_app":"memory-one","visible_when":"always"}
                }
              ]
            }
            """;
        JsonNode root = JSON.readTree(body);

        List<Map<String, Object>> parsed = invokeFetchSkills("world", root);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).get("name")).isEqualTo("world_design");
        assertThat(parsed.get(0).get("app_id")).isEqualTo("world");
        assertThat(parsed.get(0).get("visibility")).isEqualTo(List.of("llm", "ui"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) parsed.get(0).get("scope");
        assertThat(scope)
                .containsEntry("level",        "app")
                .containsEntry("owner_app",    "world")
                .containsEntry("visible_when", "always");
        assertThat(parsed.get(1).get("visibility")).isEqualTo(List.of("host"));
    }

    @Test
    void fetchSkills_fallsBackToLegacySkillsField() throws Exception {
        String body = """
            {
              "app": "world",
              "version": "1.0",
              "skills": [
                {
                  "name": "world_list",
                  "description": "列出世界",
                  "parameters": {"type":"object","properties":{},"required":[]}
                }
              ]
            }
            """;
        JsonNode root = JSON.readTree(body);

        List<Map<String, Object>> parsed = invokeFetchSkills("world", root);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).get("name")).isEqualTo("world_list");
        assertThat(parsed.get(0).get("app_id")).isEqualTo("world");
        assertThat(parsed.get(0).get("visibility")).isNull();  // 旧 payload 无 visibility 字段
    }

    @Test
    void fetchSkills_preferToolsWhenBothPresent() throws Exception {
        String body = """
            {
              "tools":  [{"name":"from_tools"}],
              "skills": [{"name":"from_skills"}]
            }
            """;
        JsonNode root = JSON.readTree(body);

        List<Map<String, Object>> parsed = invokeFetchSkills("x", root);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).get("name")).isEqualTo("from_tools");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> invokeFetchSkills(String appId, JsonNode root) throws Exception {
        AppRegistry reg = new AppRegistry();
        Method m = AppRegistry.class.getDeclaredMethod("fetchSkills", String.class, JsonNode.class);
        m.setAccessible(true);
        return (List<Map<String, Object>>) m.invoke(reg, appId, root);
    }
}
