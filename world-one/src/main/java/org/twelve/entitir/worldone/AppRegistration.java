package org.twelve.entitir.worldone;

import java.util.List;
import java.util.Map;

/**
 * 已注册的 AI-Native Application 描述信息。
 *
 * <p>由 {@link AppRegistry} 在启动时从 {@code ~/.ones/apps/} 中加载，
 * 并通过调用 app 的 {@code /api/skills} 和 {@code /api/widgets} 端点填充。
 */
public record AppRegistration(
    String appId,
    String name,
    String baseUrl,
    String systemPromptContribution,
    List<Map<String, Object>> promptContributions,
    List<Map<String, Object>> skills,
    List<Map<String, Object>> widgets
) {
    /**
     * 工具调用执行 URL。
     * @param toolName 工具名（snake_case）
     */
    public String toolUrl(String toolName) {
        return baseUrl + "/api/tools/" + toolName;
    }
}
