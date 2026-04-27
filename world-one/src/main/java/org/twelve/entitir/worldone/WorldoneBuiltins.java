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
     * World One 宿主层的 system prompt —— 只声明 **自己提供** 的能力
     * （即 {@code app_list_view}），绝不提及任何由外部 app 注册进来的 skill。
     *
     * <p>这样 world-entitir / memory-one 等 app 卸载时，本 prompt 也不会遗留
     * 对不存在 skill 的"规劝"。跨域互斥规则由各 app 的 {@code prompt_contributions}
     * （AAP-Pre 层）自行贡献（见 {@code SkillsController.AAP_PRE_SYSTEM_PROMPT}）。
     */
    /**
     * 宿主内置 app 自身的 systemPromptContribution 留空：
     * {@code app_list_view} 的路由规则完全由 {@code AppRegistry.hostSystemPrompt()}
     * 里的 "宿主域：应用" 段落统一管理（那段会动态注入 app 清单），避免重复。
     */
    private static String systemPrompt() {
        return "";
    }

    private static Map<String, Object> appListViewSkill() {
        // LinkedHashMap（而非 Map.of）以便携带 visibility + scope 元字段，且按协议顺序。
        java.util.LinkedHashMap<String, Object> t = new java.util.LinkedHashMap<>();
        t.put("name", "app_list_view");
        t.put("description",
            "打开 **AIPP 应用（app / 插件 / 功能模块）列表面板**，展示已注册应用。" +
            "命中条件和 query 语义过滤的完整规则见 system prompt 中的 " +
            "\"宿主域：应用\" 段落。" +
            "简要：用户明说 '应用/app/插件/功能模块' 才可调用；若带主题词" +
            "（如'记忆/memory/本体/HR'），把主题词作为 `query` 传入，由工具读取最新 registry 并过滤；" +
            "用户说'世界/本体/ontology'时**绝不调用本工具**。");
        t.put("parameters", Map.of(
            "type",       "object",
            "properties", Map.of(
                "query", Map.of(
                    "type",        "string",
                    "description", "可选主题过滤词。用户说'列出所有应用'时省略；用户说'记忆相关应用'时传 `记忆`。"
                ),
                "ids", Map.of(
                    "type",        "array",
                    "items",       Map.of("type", "string"),
                    "description", "兼容旧协议的精确 app_id 过滤。新调用优先使用 `query`，不要为了回答列表请求编造 ids。"
                )
            ),
            "required",   List.of()
        ));
        // Phase 6：宿主内置 tool 补齐 Tool 模型元字段，与 AIPP app 的 /api/tools 对齐。
        t.put("visibility", List.of("llm", "ui"));
        t.put("scope", Map.of(
            "level",        "universal",
            "owner_app",    "worldone-system",
            "visible_when", "always"
        ));
        return t;
    }

}
