package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI-Native Application 注册表。
 *
 * <p>存储位置（跨平台）：
 * <pre>
 *   macOS / Linux : ~/.ones/apps/{app-id}/manifest.json
 *   Windows       : C:\Users\{name}\.ones\apps\{app-id}\manifest.json
 * </pre>
 *
 * <p><b>ones</b> 是所有 AI Agent 共享的注册中心根目录，不限于 World One。
 *
 * <p>启动时自动扫描目录，对每个 manifest 调用 app 的
 * {@code /api/skills} 和 {@code /api/widgets} 端点，
 * 将结果缓存到内存供 {@link GenericAgentLoop} 使用。
 */
@Component
public class AppRegistry {

    private static final Logger log = LoggerFactory.getLogger(AppRegistry.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path APPS_ROOT =
        Paths.get(System.getProperty("user.home"), ".ones", "apps");

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /** 运行期补加载缺失 app 的最小间隔，避免高频请求重复扫描。 */
    private static final long RUNTIME_REFRESH_INTERVAL_MS = 5000L;
    private volatile long lastRuntimeRefreshMs = 0L;

    /** appId → AppRegistration */
    private final Map<String, AppRegistration> registry = new ConcurrentHashMap<>();

    /**
     * toolName → AppRegistration（快速路由）。
     *
     * <p>包含两类 tool：
     * <ol>
     *   <li>Skill 对应的 tool（world_design、world_list），供 LLM 调用路由</li>
     *   <li>Widget internal_tools（world_add_definition 等），供 ToolProxy 调用路由；
     *       这些 tool 不暴露给 LLM，但在 AppRegistry 中有路由记录。</li>
     * </ol>
     */
    private final Map<String, AppRegistration> toolIndex = new ConcurrentHashMap<>();

    /** widget_type → context_prompt（进入 canvas mode 时注入 LLM 的领域上下文） */
    private final Map<String, String> widgetContextIndex = new ConcurrentHashMap<>();

    /** widget_type → welcome_message（进入 canvas mode 时显示给用户的欢迎语） */
    private final Map<String, String> widgetWelcomeIndex = new ConcurrentHashMap<>();

    /** widget_type → display title（用于创建 task session 的名称） */
    private final Map<String, String> widgetTitleIndex = new ConcurrentHashMap<>();

    /** widget_type → canvas_skill.tools（OpenAI function-call 格式）。
     *
     * <p>进入某个 widget 的 canvas 模式后，这些工具动态追加到 LLM 的可见工具列表，
     * 退出 canvas 后移除。工具定义来源于 widget manifest 的 {@code canvas_skill.tools} 字段。
     */
    private final Map<String, List<Map<String, Object>>> widgetCanvasToolsIndex = new ConcurrentHashMap<>();

    /**
     * skill_name → widget_type。
     *
     * <p>当 GenericAgentLoop 执行某个 skill 后，查此表确定是否需要生成 canvas 事件。
     * 数据来源：widget manifest 中的 {@code renders_output_of_skill} 字段。
     */
    private final Map<String, String> skillOutputWidgetIndex = new ConcurrentHashMap<>();

    /**
     * skill_name → inject_context 配置。
     *
     * <p>AIPP 协议扩展：skill 声明需要 worldone 自动注入哪些上下文信息。
     * <ul>
     *   <li>{@code request_context: true} → worldone 注入 {@code _context.userId/sessionId/agentId}</li>
     *   <li>{@code turn_messages: true}   → worldone 额外注入完整本轮消息列表</li>
     * </ul>
     */
    private final Map<String, Map<String, Object>> skillInjectContextIndex = new ConcurrentHashMap<>();

    // ── View / Refresh 索引（AIPP Widget View 协议）──────────────────────────

    /**
     * widget_type → views list（每项包含 id / label / llm_hint）。
     * 来源：widget manifest 的 {@code views} 字段。
     */
    private final Map<String, List<Map<String, Object>>> widgetViewsIndex = new ConcurrentHashMap<>();

    /**
     * widget_type → refresh_skill name。
     * 来源：widget manifest 的 {@code refresh_skill} 字段。
     */
    private final Map<String, String> widgetRefreshSkillIndex = new ConcurrentHashMap<>();

    /**
     * widget_type → Set of mutating tool names。
     * 来源：widget manifest 的 {@code mutating_tools} 字段。
     */
    private final Map<String, Set<String>> widgetMutatingToolsIndex = new ConcurrentHashMap<>();

    // ── App Manifest 索引（AIPP App Identity 协议）───────────────────────────

    /**
     * appId → app manifest（来自 /api/app）。
     * 包含 app_name, app_icon, app_description, app_color, is_active, version。
     */
    private final Map<String, Map<String, Object>> appManifestIndex = new ConcurrentHashMap<>();

    /**
     * appId → main widget type（is_main=true 的 widget）。
     * 用于 Apps 面板点击图标时找到对应的入口 widget。
     */
    private final Map<String, String> appMainWidgetIndex = new ConcurrentHashMap<>();

    /**
     * widget_type → is_canvas_mode（true=Canvas Mode，false=Chat 内嵌 html_widget）。
     */
    private final Map<String, Boolean> widgetCanvasModeIndex = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadAll() {
        if (!Files.exists(APPS_ROOT)) {
            log.info("Registry directory not found: {}. No apps loaded.", APPS_ROOT);
            return;
        }
        File[] appDirs = APPS_ROOT.toFile().listFiles(File::isDirectory);
        if (appDirs == null || appDirs.length == 0) {
            log.info("No app directories found in {}", APPS_ROOT);
            return;
        }
        // Try up to 3 times with 3s delay to handle race condition where
        // external apps (e.g. memory-one) start concurrently with world-one.
        for (File appDir : appDirs) {
            boolean loaded = false;
            for (int attempt = 1; attempt <= 3 && !loaded; attempt++) {
                try {
                    loadApp(appDir.toPath());
                    loaded = true;
                } catch (Exception e) {
                    if (attempt < 3) {
                        log.warn("Failed to load app from {} (attempt {}), retrying in 3s: {}",
                                appDir.getName(), attempt, e.getMessage());
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    } else {
                        log.warn("Failed to load app from {} after {} attempts: {}",
                                appDir.getName(), attempt, e.getMessage());
                    }
                }
            }
        }
        log.info("Registry loaded {} app(s): {}", registry.size(), registry.keySet());
    }

    // ── public API ────────────────────────────────────────────────────────────

    /** 所有已注册的 app。 */
    public Collection<AppRegistration> apps() {
        refreshMissingAppsIfNeeded();
        return registry.values();
    }

    /**
     * 聚合所有注册 AIPP 应用贡献的 memory_hints（Layer 1b）。
     *
     * <p>每个 skill 定义中可包含 {@code memory_hints} 字段，
     * 告诉 Memory Agent 该 skill 执行后应重点追踪哪些信息。
     * 此方法收集所有非空 hints，供 {@code MemoryAgentPromptBuilder} 使用。
     *
     * <p><b>注意：这些 hints 只用于 Memory Agent 提示词，不进入主 Agent context。</b>
     */
    public List<String> allMemoryHints() {
        refreshMissingAppsIfNeeded();
        List<String> hints = new ArrayList<>();
        for (AppRegistration app : registry.values()) {
            for (Map<String, Object> skill : app.skills()) {
                Object hint = skill.get("memory_hints");
                if (hint instanceof String s && !s.isBlank()) {
                    hints.add("[" + skill.get("name") + "] " + s.strip());
                }
            }
        }
        return hints;
    }

    /** 聚合所有 app 的 skills，返回 OpenAI function-call 格式列表。
     *  background=true 和 auto_pre_turn=true 的 skill 由 host 自动调用，不暴露给 LLM。*/
    public List<Map<String, Object>> allSkillsAsTools() {
        refreshMissingAppsIfNeeded();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (AppRegistration app : registry.values()) {
            for (Map<String, Object> skill : app.skills()) {
                if (!Boolean.TRUE.equals(skill.get("background"))
                        && !Boolean.TRUE.equals(skill.get("auto_pre_turn"))) {
                    tools.add(skill);
                }
            }
        }
        return tools;
    }

    /**
     * 返回标记了 auto_pre_turn=true 的 skill（如 memory_load），
     * 用于 host 在每轮对话开始前自动调用并注入上下文。
     * 返回 [app, skill] pair，便于获取 URL。
     */
    public List<Map.Entry<AppRegistration, Map<String, Object>>> getAutoPreTurnSkills() {
        refreshMissingAppsIfNeeded();
        List<Map.Entry<AppRegistration, Map<String, Object>>> result = new ArrayList<>();
        for (AppRegistration app : registry.values()) {
            for (Map<String, Object> skill : app.skills()) {
                if (Boolean.TRUE.equals(skill.get("auto_pre_turn"))) {
                    result.add(Map.entry(app, skill));
                }
            }
        }
        return result;
    }

    /**
     * 查找已注册 app 中名称为 {@code skillName} 的后台 skill。
     * 用于 host 自动调用（不经 LLM）。
     */
    public Map<String, Object> findBackgroundSkill(String skillName) {
        refreshMissingAppsIfNeeded();
        for (AppRegistration app : registry.values()) {
            for (Map<String, Object> skill : app.skills()) {
                if (skillName.equals(skill.get("name")) && Boolean.TRUE.equals(skill.get("background"))) {
                    return skill;
                }
            }
        }
        return null;
    }

    /** 聚合所有 app 的 system_prompt_contribution，拼接成一个完整的 system prompt。 */
    public String aggregatedSystemPrompt() {
        refreshMissingAppsIfNeeded();
        StringBuilder sb = new StringBuilder();
        sb.append("""
            你是 World One，一个通用 AI 智能体。所有回复使用中文。

            ════════════════════════════════════════
            铁律（违反即为错误响应）
            ════════════════════════════════════════
            1. 用户有明确的行动意图时（操作某事物、查询、创建、编辑、删除），
               必须调用已注册的工具，禁止仅用文字描述或假装完成了操作。
            2. 调用工具前不得输出任何解释或确认文字。
            3. 说"我已经帮你做了……"而没有实际调用工具，属于严重错误——
               用户界面不会有任何变化，用户会立刻发现你在撒谎。

            ════════════════════════════════════════
            对话历史的解读规则（适用于所有工具操作）
            ════════════════════════════════════════
            对话历史仅是"参考上下文"，不代表工具已被调用或操作已完成。
            即使历史记录中出现过"已删除/已创建/已完成"等文字，
            也不能证明对应工具实际上被调用过。
            用户每次发出操作指令，不管历史中有没有类似的回复，
            都必须重新调用相应工具来执行——不能基于历史假设操作已完成。

            ════════════════════════════════════════
            工具响应解读规范
            ════════════════════════════════════════
            工具调用成功后，系统会根据工具的响应自动完成界面切换（如进入 Canvas 模式）。
            你无需手动描述界面会如何变化，等待工具返回结果后再根据结果简洁回复用户。

            ════════════════════════════════════════
            查看/列表类操作规则（html_widget 面板）
            ════════════════════════════════════════
            "列出应用"、"查看记忆"、"显示列表"等查看类请求，每次都必须重新调用工具，
            获取最新数据展示给用户——不管历史中是否有过类似的操作记录。
            这类操作没有副作用，重复调用只会刷新展示，不会产生重复数据。
            绝对禁止用历史中的旧操作记录代替重新调用工具。

            ════════════════════════════════════════
            Session 判断规范
            ════════════════════════════════════════
            - 当前对话历史中已有某个 session_id 时，优先复用，不要重复创建新 session。
            - 用户说"继续"、"接着做"、"edit XX"、"进入XX"等意图时，
              先检查历史中是否已有匹配的 session_id，有则直接使用。

            """);
        for (AppRegistration app : registry.values()) {
            if (app.systemPromptContribution() != null && !app.systemPromptContribution().isBlank()) {
                sb.append("# ").append(app.name()).append("\n");
                sb.append(app.systemPromptContribution()).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 返回指定 session 类型的入场提示词（Layer 2：仅 task / event session 有，conversation 返回 null）。
     *
     * <p>入场提示词在 session 创建时自动注入到 {@link GenericAgentLoop} 的 system message，
     * 作用域为整个 session 生命周期（非 canvas 专属，早于 widget context prompt 注入）。
     *
     * <p>不同 session 类型可返回不同内容；未来可支持 app 通过 manifest 贡献 session prompt。
     *
     * @param sessionType "task" | "event" | "conversation" | ...
     * @return 入场提示词字符串，若无则返回 null
     */
    public String sessionEntryPrompt(String sessionType) {
        return switch (sessionType == null ? "" : sessionType) {
            case "task" -> """
                ## 任务会话规范
                工具执行成功后，用 1-2 句话说明操作结果（如"已为 Employee 添加 gender 字段"）。
                不要输出完整数据定义、JSON 内容或 Markdown 文档，除非用户明确要求展示。
                """;
            case "event" -> """
                ## 事件会话规范
                本会话由系统事件触发，保持简洁：直接描述触发原因和处理结果。
                """;
            default -> null;
        };
    }

    /**
     * 返回指定 widget type 的 context_prompt。
     * 当 agent loop 检测到 canvas 进入该 widget type 时调用，将结果追加到 system message。
     *
     * @param widgetType 如 "entity-graph"
     * @return context_prompt 字符串，如果该 widget 没有注册 context_prompt 则返回 null
     */
    public String widgetContextPrompt(String widgetType) {
        return widgetContextIndex.get(widgetType);
    }

    /**
     * 返回指定 widget type 的 canvas_skill 工具列表（OpenAI function-call 格式）。
     *
     * <p>进入 canvas 模式时由 {@link GenericAgentLoop} 调用，
     * 将这些工具动态追加到 LLM 的可见工具列表，退出 canvas 后不再注入。
     *
     * @param widgetType 如 "entity-graph"
     * @return canvas 工具列表，若无则返回空列表
     */
    public List<Map<String, Object>> getCanvasTools(String widgetType) {
        if (widgetType == null) return List.of();
        return widgetCanvasToolsIndex.getOrDefault(widgetType, List.of());
    }

    /**
     * 根据工具名找到对应的 app。
     * 包含 skill-level tool 和 widget internal tool。
     * @throws IllegalArgumentException 如果找不到
     */
    public AppRegistration findAppForTool(String toolName) {
        AppRegistration app = toolIndex.get(toolName);
        if (app == null) throw new IllegalArgumentException("No app found for tool: " + toolName);
        return app;
    }

    /**
     * 查询某个 skill 执行后应该用哪个 widget 渲染输出。
     *
     * @param skillName skill 名（如 "world_design"）
     * @return widget type 字符串（如 "entity-graph"），若该 skill 无 widget 则返回 null
     */
    public String findOutputWidgetForSkill(String skillName) {
        return skillOutputWidgetIndex.get(skillName);
    }

    /**
     * 返回 skill 声明的 inject_context 配置（AIPP 协议扩展）。
     *
     * <p>GenericAgentLoop 调用工具前检查此配置，决定是否在请求体中额外注入：
     * <ul>
     *   <li>{@code request_context: true} → 注入 _context（userId, sessionId, agentId）</li>
     *   <li>{@code turn_messages: true}   → 还注入完整本轮消息列表</li>
     * </ul>
     *
     * @param skillName 工具/skill 名
     * @return inject_context map，若无声明则返回空 map
     */
    public Map<String, Object> getSkillInjectContext(String skillName) {
        return skillInjectContextIndex.getOrDefault(skillName, Map.of());
    }

    /**
     * 检查 skill 是否需要注入本轮消息（inject_context.turn_messages=true）。
     */
    public boolean requiresTurnMessages(String skillName) {
        Object v = getSkillInjectContext(skillName).get("turn_messages");
        return Boolean.TRUE.equals(v);
    }

    // ── View / Refresh 协议（AIPP Widget View）──────────────────────────────

    /**
     * 根据 (widgetType, viewId) 构建注入给 LLM 的 UI 上下文 hints 列表。
     *
     * <p>逻辑：
     * <ol>
     *   <li>查找 widget 中 {@code id == viewId} 的视图，取其 {@code llm_hint}；</li>
     *   <li>将 hint 中的 {@code {refresh_skill}} 占位符替换为实际 skill 名；</li>
     *   <li>追加一条通用刷新指令，告知 LLM 在执行 mutating_tools 后必须调用 refresh_skill。</li>
     * </ol>
     *
     * <p>返回空列表表示该 (widgetType, viewId) 无对应配置。
     *
     * @param widgetType 如 "memory-manager"，来自前端 widget_view.widget_type
     * @param viewId     如 "RELATION"，来自前端 widget_view.view_id
     * @return 注入 LLM 的 hint 字符串列表（host 包裹成最高优先级 system 块注入）
     */
    public List<String> buildUiHints(String widgetType, String viewId) {
        if (widgetType == null || widgetType.isBlank()) return List.of();

        String refreshSkill = widgetRefreshSkillIndex.get(widgetType);
        List<Map<String, Object>> views = widgetViewsIndex.get(widgetType);
        Set<String> mutatingTools = widgetMutatingToolsIndex.getOrDefault(widgetType, Set.of());

        List<String> hints = new ArrayList<>();

        // 1. View-level hint
        if (views != null && viewId != null && !viewId.isBlank()) {
            for (Map<String, Object> view : views) {
                if (viewId.equals(view.get("id"))) {
                    String hint = String.valueOf(view.get("llm_hint"));
                    if (refreshSkill != null) hint = hint.replace("{refresh_skill}", refreshSkill);
                    hints.add(hint);
                    break;
                }
            }
        }

        // 2. Mutating-tools refresh reminder
        if (refreshSkill != null && !mutatingTools.isEmpty()) {
            hints.add("如果本次操作调用了以下任意工具：" + String.join("、", mutatingTools) +
                      "，操作完成后必须调用 " + refreshSkill + " 刷新 widget 数据展示（若 LLM 未调用，Host 将自动兜底）。");
        }

        return hints;
    }

    /**
     * 返回指定 widget 的 refresh_skill 名称。
     *
     * @param widgetType widget 类型，如 "memory-manager"
     * @return skill 名称，未配置时返回 null
     */
    public String getWidgetRefreshSkill(String widgetType) {
        if (widgetType == null) return null;
        return widgetRefreshSkillIndex.get(widgetType);
    }

    /**
     * 检查指定工具是否是某 widget 的 mutating tool（变更类工具）。
     *
     * @param widgetType widget 类型
     * @param toolName   工具名
     * @return true if the tool mutates this widget's data
     */
    public boolean isWidgetMutatingTool(String widgetType, String toolName) {
        if (widgetType == null || toolName == null) return false;
        return widgetMutatingToolsIndex.getOrDefault(widgetType, Set.of()).contains(toolName);
    }

    /**
     * 查询某个 widget 进入 canvas session 时显示给用户的欢迎语。
     *
     * @param widgetType widget type（如 "entity-graph"）
     * @return 欢迎语，未配置时返回 null
     */
    public String widgetWelcomeMessage(String widgetType) {
        return widgetType != null ? widgetWelcomeIndex.get(widgetType) : null;
    }

    /**
     * 返回 widget 的显示名称（用于创建 task session 的名称）。
     *
     * @param widgetType widget type（如 "memory-manager"）
     * @return 显示名称，未配置时返回 widgetType 本身
     */
    public String widgetTitle(String widgetType) {
        if (widgetType == null) return "任务";
        return widgetTitleIndex.getOrDefault(widgetType, widgetType);
    }

    /**
     * 安装 app：将 manifest.json 写入 ~/.ones/apps/{appId}/manifest.json，
     * 然后加载（调用 /api/skills 等端点）。
     */
    public void install(String appId, String baseUrl) throws Exception {
        Path appDir = APPS_ROOT.resolve(appId);
        Files.createDirectories(appDir);
        Map<String, Object> manifest = Map.of(
            "id", appId,
            "api", Map.of("base_url", baseUrl)
        );
        Files.writeString(appDir.resolve("manifest.json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        loadApp(appDir);
        log.info("Installed app: {} at {}", appId, baseUrl);
    }

    /**
     * 注册 worldone 内置 app（不通过 HTTP，直接注入 skills/widgets）。
     * 由 {@code WorldoneBuiltins} 在 Spring 容器启动后调用。
     *
     * @param appId   应用标识，如 "worldone"
     * @param name    显示名称
     * @param baseUrl 本地 HTTP 基地址（如 "http://localhost:8090"），供 GenericAgentLoop 调用工具
     * @param systemPromptContribution 贡献给 Layer 1 的 system prompt 片段（可为空）
     * @param skills  skill 定义列表（OpenAI function-call 格式）
     * @param widgets widget 定义列表（AIPP widget 格式）
     */
    public void registerBuiltin(String appId, String name, String baseUrl,
                                String systemPromptContribution,
                                List<Map<String, Object>> skills,
                                List<Map<String, Object>> widgets) {
        AppRegistration reg = new AppRegistration(appId, name, baseUrl,
                systemPromptContribution, skills, widgets);
        registry.put(appId, reg);

        for (Map<String, Object> skill : skills) {
            Object toolName = skill.get("name");
            if (toolName != null) {
                toolIndex.put(toolName.toString(), reg);
                Object ic = skill.get("inject_context");
                if (ic instanceof Map<?, ?> icMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> icTyped = (Map<String, Object>) icMap;
                    skillInjectContextIndex.put(toolName.toString(), icTyped);
                }
            }
        }

        for (Map<String, Object> widget : widgets) {
            Object type = widget.get("type");

            Object ctx = widget.get("context_prompt");
            if (type != null && ctx != null) {
                widgetContextIndex.put(type.toString(), ctx.toString());
            }

            Object welcome = widget.get("welcome_message");
            if (type != null && welcome != null) {
                widgetWelcomeIndex.put(type.toString(), welcome.toString());
            }

            Object desc = widget.get("description");
            if (type != null && desc != null) {
                String raw   = desc.toString();
                int    cut   = raw.indexOf('：');
                String title = cut > 0 ? raw.substring(0, cut) : (raw.length() > 12 ? raw.substring(0, 12) : raw);
                widgetTitleIndex.put(type.toString(), title);
            }

            Object canvasSkill = widget.get("canvas_skill");
            if (type != null && canvasSkill instanceof Map<?, ?> csMap) {
                Object toolsNode = csMap.get("tools");
                if (toolsNode instanceof List<?> toolsList) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tools = (List<Map<String, Object>>) toolsList;
                    widgetCanvasToolsIndex.put(type.toString(), tools);
                }
            }

            Object rendersFor = widget.get("renders_output_of_skill");
            if (type != null && rendersFor != null) {
                skillOutputWidgetIndex.put(rendersFor.toString(), type.toString());
            }

            Object internalTools = widget.get("internal_tools");
            if (internalTools instanceof List<?> toolList) {
                for (Object tool : toolList) {
                    if (tool != null) toolIndex.put(tool.toString(), reg);
                }
            }

            // views / refresh_skill / mutating_tools（AIPP Widget View 协议）
            indexWidgetViewFields(type, widget);

            // app_id / is_main / is_canvas_mode（AIPP App Identity 协议）
            indexWidgetAppIdentity(type, widget);
        }
        log.info("Registered builtin app: {} ({} skills, {} widgets)",
                appId, skills.size(), widgets.size());
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private void loadApp(Path appDir) throws Exception {
        Path manifestFile = appDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            log.warn("No manifest.json in {}, skipping", appDir);
            return;
        }
        JsonNode manifest = JSON.readTree(Files.readString(manifestFile));
        String appId   = manifest.path("id").asText();
        String baseUrl = manifest.path("api").path("base_url").asText();

        if (appId.isBlank() || baseUrl.isBlank()) {
            log.warn("manifest.json in {} is missing id or base_url, skipping", appDir);
            return;
        }

        List<Map<String, Object>> skills  = fetchSkills(appId, baseUrl);
        List<Map<String, Object>> widgets = fetchWidgets(appId, baseUrl);
        String systemPrompt = fetchSystemPrompt(appId, baseUrl);
        String name = manifest.path("name").asText(appId);

        AppRegistration reg = new AppRegistration(appId, name, baseUrl, systemPrompt, skills, widgets);
        registry.put(appId, reg);

        for (Map<String, Object> skill : skills) {
            Object toolName = skill.get("name");
            if (toolName != null) {
                toolIndex.put(toolName.toString(), reg);
                // Index inject_context per skill (AIPP protocol extension)
                Object ic = skill.get("inject_context");
                if (ic instanceof Map<?, ?> icMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> icTyped = (Map<String, Object>) icMap;
                    skillInjectContextIndex.put(toolName.toString(), icTyped);
                }
            }
        }

        for (Map<String, Object> widget : widgets) {
            Object type = widget.get("type");

            // widget_type → context_prompt（canvas 模式下注入 LLM）
            Object ctx = widget.get("context_prompt");
            if (type != null && ctx != null) {
                widgetContextIndex.put(type.toString(), ctx.toString());
            }

            // widget_type → welcome_message（进入 canvas session 时展示给用户）
            Object welcome = widget.get("welcome_message");
            if (type != null && welcome != null) {
                widgetWelcomeIndex.put(type.toString(), welcome.toString());
            }

            // widget_type → display title（用于 task session 名称）
            Object desc2 = widget.get("description");
            if (type != null && desc2 != null) {
                String raw   = desc2.toString();
                int    cut   = raw.indexOf('：');
                String title = cut > 0 ? raw.substring(0, cut) : (raw.length() > 12 ? raw.substring(0, 12) : raw);
                widgetTitleIndex.put(type.toString(), title);
            }

            // widget_type → canvas_skill.tools（canvas 模式下追加到 LLM 工具列表）
            Object canvasSkill = widget.get("canvas_skill");
            if (type != null && canvasSkill instanceof Map<?, ?> csMap) {
                Object toolsNode = csMap.get("tools");
                if (toolsNode instanceof List<?> toolsList) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tools = (List<Map<String, Object>>) toolsList;
                    widgetCanvasToolsIndex.put(type.toString(), tools);
                    log.debug("Registered {} canvas tools for widget: {}", tools.size(), type);
                }
            }

            // skill_name → widget_type（skill 执行后自动渲染输出）
            Object rendersFor = widget.get("renders_output_of_skill");
            if (type != null && rendersFor != null) {
                skillOutputWidgetIndex.put(rendersFor.toString(), type.toString());
                log.debug("Registered skill-output-widget: skill={} → widget={}", rendersFor, type);
            }

            // internal_tools → 路由到此 app（ToolProxy 使用，LLM 不可见）
            Object internalTools = widget.get("internal_tools");
            if (internalTools instanceof List<?> toolList) {
                for (Object tool : toolList) {
                    if (tool != null) {
                        toolIndex.put(tool.toString(), reg);
                        log.debug("Registered internal tool for ToolProxy: {}", tool);
                    }
                }
            }

            // views / refresh_skill / mutating_tools（AIPP Widget View 协议）
            indexWidgetViewFields(type, widget);

            // app_id / is_main / is_canvas_mode（AIPP App Identity 协议）
            indexWidgetAppIdentity(type, widget);
        }

        // Fetch app manifest from /api/app (optional – gracefully skip if not available)
        fetchAndIndexAppManifest(appId, baseUrl);
    }

    /** 索引 widget 的 views / refresh_skill / mutating_tools 字段（供 buildUiHints 使用）。 */
    @SuppressWarnings("unchecked")
    private void indexWidgetViewFields(Object type, Map<String, Object> widget) {
        if (type == null) return;
        String wt = type.toString();

        Object viewsObj = widget.get("views");
        if (viewsObj instanceof List<?> vList) {
            List<Map<String, Object>> views = new ArrayList<>();
            for (Object v : vList) {
                if (v instanceof Map<?, ?> vm) views.add((Map<String, Object>) vm);
            }
            if (!views.isEmpty()) {
                widgetViewsIndex.put(wt, views);
                log.debug("Registered {} views for widget: {}", views.size(), wt);
            }
        }

        Object refreshSkill = widget.get("refresh_skill");
        if (refreshSkill != null && !refreshSkill.toString().isBlank()) {
            widgetRefreshSkillIndex.put(wt, refreshSkill.toString());
        }

        Object mutatingToolsObj = widget.get("mutating_tools");
        if (mutatingToolsObj instanceof List<?> mtList) {
            Set<String> tools = new HashSet<>();
            for (Object t : mtList) {
                if (t != null && !t.toString().isBlank()) tools.add(t.toString());
            }
            if (!tools.isEmpty()) widgetMutatingToolsIndex.put(wt, tools);
        }
    }

    /** 索引 widget 的 app_id / is_main / is_canvas_mode 字段（AIPP App Identity 协议）。 */
    private void indexWidgetAppIdentity(Object type, Map<String, Object> widget) {
        if (type == null) return;
        String wt = type.toString();

        Object isCanvasMode = widget.get("is_canvas_mode");
        boolean canvasMode = isCanvasMode == null || Boolean.TRUE.equals(isCanvasMode);
        widgetCanvasModeIndex.put(wt, canvasMode);

        Object isMain = widget.get("is_main");
        if (Boolean.TRUE.equals(isMain)) {
            Object appId = widget.get("app_id");
            if (appId != null && !appId.toString().isBlank()) {
                appMainWidgetIndex.put(appId.toString(), wt);
                log.debug("Registered main widget for app {}: {}", appId, wt);
            }
        }
    }

    /** 从 /api/app 读取 app manifest，缓存到 appManifestIndex。若端点不存在，静默跳过。 */
    @SuppressWarnings("unchecked")
    private void fetchAndIndexAppManifest(String appId, String baseUrl) {
        try {
            String body = get(baseUrl + "/api/app");
            Map<String, Object> manifest = JSON.readValue(body, Map.class);
            appManifestIndex.put(appId, manifest);
            log.debug("Loaded app manifest for: {}", appId);
        } catch (Exception e) {
            log.debug("No /api/app endpoint for {}: {}", appId, e.getMessage());
        }
    }

    /**
     * 返回所有已注册 app 的 manifest 列表（供 GET /api/apps 使用）。
     * 若某 app 没有 /api/app 端点，则补全 app_id 和 app_name（来自 AppRegistration）作为最小 manifest。
     */
    public List<Map<String, Object>> buildAppsManifests() {
        refreshMissingAppsIfNeeded();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppRegistration reg : registry.values()) {
            Map<String, Object> m = appManifestIndex.getOrDefault(reg.appId(), null);
            if (m != null) {
                // 追加 main_widget_type 供前端直接使用
                Map<String, Object> enriched = new LinkedHashMap<>(m);
                String mainWidget = appMainWidgetIndex.get(reg.appId());
                if (mainWidget != null) enriched.put("main_widget_type", mainWidget);
                result.add(enriched);
            } else {
                // 最小 manifest（没有 /api/app 的内置 app）
                Map<String, Object> min = new LinkedHashMap<>();
                min.put("app_id",          reg.appId());
                min.put("app_name",        reg.name());
                min.put("app_icon",        "");
                min.put("app_description", "");
                min.put("app_color",       "#6b7a9e");
                min.put("is_active",       true);
                min.put("version",         "");
                String mainWidget = appMainWidgetIndex.get(reg.appId());
                if (mainWidget != null) min.put("main_widget_type", mainWidget);
                result.add(min);
            }
        }
        return result;
    }

    /** 查找 appId 对应的 main widget type；无 is_main=true widget 时返回 null。 */
    public String getAppMainWidgetType(String appId) {
        refreshMissingAppsIfNeeded();
        return appManifestIndex.containsKey(appId) || registry.containsKey(appId)
                ? appMainWidgetIndex.get(appId)
                : null;
    }

    /** 返回 widgetType 的 is_canvas_mode 值；未注册时默认 true（Canvas 模式）。 */
    public boolean isWidgetCanvasMode(String widgetType) {
        return widgetType == null || widgetCanvasModeIndex.getOrDefault(widgetType, true);
    }

    /**
     * 根据 widgetType 反查 renders_output_of_skill（入口 skill）。
     * 供 openApp() 找到 main widget 对应的 skill 直接调用。
     * 若无对应 skill 返回 null。
     */
    public String findOutputSkillForWidget(String widgetType) {
        refreshMissingAppsIfNeeded();
        if (widgetType == null) return null;
        for (Map.Entry<String, String> e : skillOutputWidgetIndex.entrySet()) {
            if (widgetType.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchSkills(String appId, String baseUrl) throws Exception {
        String body = get(baseUrl + "/api/skills");
        JsonNode root = JSON.readTree(body);
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode skill : root.path("skills")) {
            Map<String, Object> s = JSON.treeToValue(skill, Map.class);
            s.put("app_id", appId);
            result.add(s);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchWidgets(String appId, String baseUrl) throws Exception {
        String body = JSON.readTree(get(baseUrl + "/api/widgets"))
                          .path("widgets").toString();
        return JSON.readValue(body,
            JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    private String fetchSystemPrompt(String appId, String baseUrl) {
        try {
            JsonNode root = JSON.readTree(get(baseUrl + "/api/skills"));
            return root.path("system_prompt").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + url);
        return resp.body();
    }

    /**
     * 运行期补加载缺失 app。
     *
     * <p>场景：外部 app（如 memory-one）在 world-one 启动时尚未就绪，启动阶段加载失败；
     * 之后当 app 端口可用，本方法会在常规请求链路上补注册，避免必须重启 world-one。
     */
    private void refreshMissingAppsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRuntimeRefreshMs < RUNTIME_REFRESH_INTERVAL_MS) return;
        synchronized (this) {
            now = System.currentTimeMillis();
            if (now - lastRuntimeRefreshMs < RUNTIME_REFRESH_INTERVAL_MS) return;
            lastRuntimeRefreshMs = now;

            if (!Files.exists(APPS_ROOT)) return;
            File[] appDirs = APPS_ROOT.toFile().listFiles(File::isDirectory);
            if (appDirs == null || appDirs.length == 0) return;

            for (File appDir : appDirs) {
                String dirName = appDir.getName();
                if (registry.containsKey(dirName)) continue;
                try {
                    loadApp(appDir.toPath());
                    if (registry.containsKey(dirName)) {
                        log.info("Runtime app refresh loaded: {}", dirName);
                    }
                } catch (Exception e) {
                    log.debug("Runtime app refresh skipped {}: {}", dirName, e.getMessage());
                }
            }
        }
    }
}
