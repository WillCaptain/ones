package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 注册 World One 自身的内置系统 skills。
 *
 * <p>内置 skill 与远端 AIPP app 走相同的标准路径：
 * <ol>
 *   <li>LLM 从 {@code allSkillsAsTools()} 获得工具定义</li>
 *   <li>{@code GenericAgentLoop.callToolViaHttp()} 路由到 worldone 自身的 HTTP 端点</li>
 *   <li>{@link WorldoneSystemSkillsController} 处理请求，返回 html_widget JSON</li>
 *   <li>{@code extractEvents} 提取 html_widget 事件，嵌入当前对话</li>
 * </ol>
 *
 * <p>Panel 面板也通过同一 Controller 的 GET 端点加载 iframe。
 */
@Component
public class WorldoneBuiltins {

    @Autowired AppRegistry registry;
    @Value("${server.port:8090}") int port;

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        registry.registerBuiltin(
            "worldone-system",
            "World One 系统",
            "http://localhost:" + port,
            systemPrompt(),
            List.of(appListViewSkill()),
            List.of()
        );
    }

    /**
     * 注入 LLM 的系统提示：明确"AIPP 应用/插件"与"本体世界"的语义边界，
     * 防止 LLM 将"列出应用"误路由到 world_list。
     */
    private static String systemPrompt() {
        return """
            ## 工具路由规范（严格执行，禁止违反）

            | 用户说的词 | 必须调用 | 禁止调用 |
            |---|---|---|
            | 应用 / app / 应用列表 | app_list_view | world_list |
            | 世界 / 本体世界 / 世界列表 | world_list | app_list_view |

            **示例（必须照此执行）：**
            - "列出所有应用" → app_list_view
            - "有哪些应用" → app_list_view
            - "列出所有世界" → world_list
            - "有哪些世界" → world_list

            ⚠️ 绝对禁止：用户说"应用"时调用 world_list，这是错误路由。
            ⚠️ 绝对禁止：用户说"世界"时调用 app_list_view，这是错误路由。
            """;
    }

    private static Map<String, Object> appListViewSkill() {
        return Map.of(
            "name",        "app_list_view",
            "description", "列出注册到 World One 的所有 AIPP 应用（world-entitir、memory-one 等功能模块）。" +
                           "【精确触发词】：'应用'、'列出应用'、'有哪些应用'、'应用列表'。" +
                           "【绝对禁止】：用户说'世界'时不得调用此工具，应改用 world_list。" +
                           "可选 query 参数按名称/描述过滤，留空则列出全部。",
            "parameters",  Map.of(
                "type",       "object",
                "properties", Map.of(
                    "query", Map.of(
                        "type",        "string",
                        "description", "关键词过滤（按应用名称或描述），留空则列出全部应用"
                    )
                ),
                "required",   List.of()
            )
        );
    }
}
