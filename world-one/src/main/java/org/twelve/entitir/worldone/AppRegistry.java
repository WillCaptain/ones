package org.twelve.entitir.worldone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
        System.getProperty("ones.apps.root") != null
            ? Paths.get(System.getProperty("ones.apps.root"))
            : Paths.get(System.getProperty("user.home"), ".ones", "apps");

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /** 运行期补加载缺失 app 的最小间隔，避免高频请求重复扫描。 */
    private static final long RUNTIME_REFRESH_INTERVAL_MS = 5000L;
    private volatile long lastRuntimeRefreshMs = 0L;

    /** appId → AppRegistration */
    private final Map<String, AppRegistration> registry = new ConcurrentHashMap<>();
    @Autowired private WorldOneConfigStore configStore;
    /** appId → 最近一次加载失败原因（用于 UI 标注离线/失败 app）。 */
    private final Map<String, String> appLoadErrorIndex = new ConcurrentHashMap<>();
    /** appId → 最近一次在线探测结果。 */
    private final Map<String, Boolean> appOnlineIndex = new ConcurrentHashMap<>();
    /** appId → 最近一次在线探测时间戳。 */
    private final Map<String, Long> appOnlineCheckedAtMs = new ConcurrentHashMap<>();
    /** 在线探测最小间隔，避免每次列表请求都触发网络探测。 */
    private static final long APP_ONLINE_CHECK_INTERVAL_MS = 3000L;

    /**
     * toolName → AppRegistration（快速路由）。
     *
     * <p>包含两类 tool：
     * <ol>
     *   <li>Skill 对应的 tool（来自 AIPP 的 /api/skills），供 LLM 调用路由</li>
     *   <li>Widget internal_tools（来自 AIPP widget manifest 的 internal_tools 列表），
     *       供 ToolProxy 调用路由；这些 tool 不暴露给 LLM，但在 AppRegistry 中有路由记录。</li>
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

    /**
     * skill_name → output_widget_rules（AIPP 协议扩展，Host 解耦协议）。
     *
     * <p>{@code force_canvas_when}：String 列表；当响应 JSON 中所有列出字段都存在且非空时强制
     * 进入 canvas 模式（即便也带 html_widget）。{@code default_widget}：兜底 widget_type，用于
     * 本地未安装 widget manifest 时仍能正确路由到 canvas。
     *
     * <p>替代 host 对具体 skill 名 + 字段组合的硬编码（旧：world_design + graph + session_id）。
     */
    private final Map<String, Map<String, Object>> skillOutputWidgetRulesIndex = new ConcurrentHashMap<>();

    /**
     * skill_name → lifecycle 字符串。{@code post_turn} skill 由 host 在每轮对话结束后异步执行。
     */
    private final Map<String, String> skillLifecycleIndex = new ConcurrentHashMap<>();

    /**
     * appId → event_subscriptions（如 {@code ["workspace.changed"]}）。Host 通过通用事件总线
     * POST 到 app 的 {@code /api/events} 端点。替代 host 硬编码调用 memory_workspace_join。
     */
    private final Map<String, Set<String>> appEventSubscriptionsIndex = new ConcurrentHashMap<>();

    /**
     * event_name → (app, path)。来自 skill 或 app 顶层 {@code runtime_event_callback}。
     * 替代 ExecutorRoutingService 对具体 tool 名的硬编码定位。
     */
    private final Map<String, Map.Entry<AppRegistration, String>> runtimeEventCallbackIndex = new ConcurrentHashMap<>();

    /**
     * skill_name → display label（取自 skill manifest 的 {@code display_label_zh} 或
     * {@code display_name}）。供 host 通过 {@code GET /api/tool-labels} 暴露给前端动态查表，
     * 替代前端硬编码的 TOOL_LABELS 字典。
     */
    private final Map<String, String> toolDisplayLabelIndex = new ConcurrentHashMap<>();

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
    /** widget_type → app_id（用于判定当前 active app）。 */
    private final Map<String, String> widgetAppOwnerIndex = new ConcurrentHashMap<>();

    /**
     * widget_type → widget 级 {@code scope} 对象（tools_allow / tools_deny /
     * forbid_execution）。结构参见 {@code aipp-protocol.md} § 3.2.1。
     *
     * <p>widget 激活时由 {@link GenericAgentLoop} 应用于当前 session 的工具过滤；
     * 不负责 session 创建。
     */
    private final Map<String, Map<String, Object>> widgetScopeIndex = new ConcurrentHashMap<>();

    /** widget_type → widget 级 {@code system_prompt}（激活态 SOP，可选）。 */
    private final Map<String, String> widgetSystemPromptIndex = new ConcurrentHashMap<>();

    /**
     * widget_type → view_id → view 级 {@code scope}。
     * 与 widget 级 scope 取交集，不能放宽。
     */
    private final Map<String, Map<String, Map<String, Object>>> widgetViewScopeIndex = new ConcurrentHashMap<>();

    /** widget_type → view_id → view 级 {@code system_prompt}（激活该 view 时追加）。 */
    private final Map<String, Map<String, String>> widgetViewSystemPromptIndex = new ConcurrentHashMap<>();

    /**
     * skill_name → kind（{@code "design"} / {@code "execution"}）。
     *
     * <p>见 {@code aipp-protocol.md} § 3.1。未显式声明者一律按 {@code execution}
     * 处理（保守默认），以保证 widget {@code scope.forbid_execution} 对未标注的
     * 老 skill 也生效。
     */
    private final Map<String, String> skillKindIndex = new ConcurrentHashMap<>();
    /** 已告警的非法 prompt contribution layer（避免重复刷日志）。 */
    private final Set<String> invalidContributionLayerWarned = ConcurrentHashMap.newKeySet();

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
                        String err = e.getMessage();
                        if (err == null || err.isBlank()) err = e.getClass().getSimpleName();
                        appLoadErrorIndex.put(appDir.getName(), err);
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

    /**
     * 聚合所有 app 的 tools，返回可暴露给 LLM 的清单。
     *
     * <p>过滤规则（与 Tool 模型对齐）：
     * <ul>
     *   <li>{@code background=true} 或 {@code auto_pre_turn=true}：host 自动调用，隐藏。</li>
     *   <li>{@code visibility} 不含 {@code "llm"}：UI-only 或 host-only，隐藏（例如
     *       {@code memory_load} widget 级副本 {@code visibility=["ui"]}）。</li>
     *   <li>同名跨条目去重（app 与 widget 级可能同时登记相同 tool）：
     *       优先级 app-level &gt; widget-level，{@code visible_when=always} &gt; {@code canvas_open}。
     *       未命中优先级时保留首个出现的定义。</li>
     * </ul>
     * <p>去重是必要的：LLM API（OpenAI / Anthropic）要求 tool 名称唯一，
     * 否则整轮 400 "Tool names must be unique"。
     */
    public List<Map<String, Object>> allSkillsAsTools() {
        refreshMissingAppsIfNeeded();
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (AppRegistration app : registry.values()) {
            for (Map<String, Object> skill : app.skills()) {
                if (Boolean.TRUE.equals(skill.get("background"))) continue;
                if (Boolean.TRUE.equals(skill.get("auto_pre_turn"))) continue;
                if (!visibilityContains(skill, "llm")) continue;

                Object nameObj = skill.get("name");
                if (nameObj == null) continue;
                String name = nameObj.toString();

                Map<String, Object> existing = byName.get(name);
                if (existing == null || llmToolRank(skill) > llmToolRank(existing)) {
                    byName.put(name, skill);
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** 返回指定 app 对 LLM 可见的 tool 清单，保持与 {@link #allSkillsAsTools()} 相同过滤规则。 */
    public List<Map<String, Object>> skillsAsToolsForApp(String appId) {
        refreshMissingAppsIfNeeded();
        AppRegistration app = registry.get(appId);
        if (app == null) return List.of();
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> skill : app.skills()) {
            if (Boolean.TRUE.equals(skill.get("background"))) continue;
            if (Boolean.TRUE.equals(skill.get("auto_pre_turn"))) continue;
            if (!visibilityContains(skill, "llm")) continue;

            Object nameObj = skill.get("name");
            if (nameObj == null) continue;
            String name = nameObj.toString();

            Map<String, Object> existing = byName.get(name);
            if (existing == null || llmToolRank(skill) > llmToolRank(existing)) {
                byName.put(name, skill);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static boolean visibilityContains(Map<String, Object> tool, String token) {
        Object v = tool.get("visibility");
        if (!(v instanceof List<?> list)) {
            // Tool 未声明 visibility 时按旧默认（对 LLM 可见）兜底，保持与 Phase 1 之前行为一致。
            return "llm".equals(token);
        }
        for (Object o : list) if (token.equals(String.valueOf(o))) return true;
        return false;
    }

    /**
     * 同名 tool 去重的优先级分：app 级 &gt; widget 级；{@code always} 的可见窗口 &gt; {@code canvas_open}。
     */
    @SuppressWarnings("unchecked")
    private static int llmToolRank(Map<String, Object> tool) {
        Object scopeObj = tool.get("scope");
        if (!(scopeObj instanceof Map<?, ?> sMap)) return 0;
        Map<String, Object> scope = (Map<String, Object>) sMap;
        String level = String.valueOf(scope.get("level"));
        String when  = String.valueOf(scope.get("visible_when"));
        int rank = switch (level) {
            case "universal" -> 3;
            case "app"       -> 2;
            case "widget"    -> 1;
            default          -> 0;
        };
        if ("always".equals(when)) rank += 10; // 优先"一直可见"的副本
        return rank;
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

    /**
     * Host 全局规则。
     *
     * <p><b>解耦原则</b>：本提示词不包含任何具体 AIPP 名字、tool 名、widget 类型或领域词。
     * 各 AIPP 的领域规则通过 {@code prompt_contributions[layer=aap_pre]} 自行贡献，
     * 由 {@link #aggregatedPrePrompt(java.util.Set)} 拼装。
     */
    public String hostSystemPrompt() {
        return """
            你是 World One，一个通用 AI 智能体宿主（host）。所有回复使用中文。

            ════════════════════════════════════════
            铁律（违反即为错误响应）
            ════════════════════════════════════════
            1. 用户有明确的行动意图时（操作某事物、查询、创建、编辑、删除），
               必须调用已注册的工具，禁止仅用文字描述或假装完成了操作。
            2. 调用工具前不得输出任何解释或确认文字。
            3. 说"我已经帮你做了……"而没有实际调用工具，属于严重错误——
               用户界面不会有任何变化，用户会立刻发现你在撒谎。

            ════════════════════════════════════════
            对话历史的解读规则
            ════════════════════════════════════════
            对话历史仅是"参考上下文"，不代表工具已被调用或操作已完成。
            即使历史记录中出现过"已删除/已创建/已完成"等文字，
            也不能证明对应工具实际上被调用过。
            用户每次发出操作指令都必须重新调用相应工具——不能基于历史假设操作已完成。

            ════════════════════════════════════════
            动态视图查询规则（html_widget / canvas / session 面板）
            ════════════════════════════════════════
            用户请求"列出/查看/显示/打开/进入/管理/配置"某类动态对象时，
            不要根据历史消息、system prompt 中的示例或你自己的知识直接回答。
            你必须先判断是否存在匹配的 tool / skill / widget view：
            - 如果存在，必须调用对应工具获取最新结果。
            - 如果工具返回 html_widget / canvas / session / view 事件，该界面就是最终展示结果。
            - 返回界面后，不要再用普通文字复述列表内容。
            - 如果没有匹配工具，才可以说明"当前没有可用工具展示该内容"。

            历史中的列表结果只能说明"曾经展示过"，不能代表当前状态。
            每一次用户再次请求列表、查看或管理，都必须重新调用工具刷新。

            ════════════════════════════════════════
            Session 判断规范
            ════════════════════════════════════════
            - 当前对话历史中已有某个 session_id 时，优先复用，不要重复创建新 session。
            - 用户说"继续"、"接着做"、"进入 XX"等意图时，
              先检查历史中是否已有匹配的 session_id，有则直接使用。
            """ + buildAppDomainSection();
    }

    /**
     * Host 自有域：应用注册中心（app / 插件 / 功能模块）。
     *
     * <p>本段只声明 {@code app_list_view} 的命中触发词与参数语义。<b>不</b>枚举任何
     * 具体 AIPP（如世界、记忆等）的主题词；所有具体领域路由由各 AIPP 的
     * {@code prompt_contributions[layer=aap_pre]} 自行贡献。
     */
    private String buildAppDomainSection() {
        return "\n\n════════════════════════════════════════\n"
             + "宿主域：应用列表（app / 插件 / 功能模块）\n"
             + "════════════════════════════════════════\n"
             + "【命中本域的触发词（必须是用户明说）】\n"
             + "  应用 / app / 插件 / 功能模块 / 已安装了哪些\n"
             + "  → 调用 `app_list_view`\n\n"
             + "【query 参数抽取】\n"
             + "  - 用户无主题词（\"列出所有应用\"、\"有哪些应用\"）：调用 app_list_view()，不传 query。\n"
             + "  - 用户带任意主题词（如 \"X 相关应用\"、\"X 插件\"）：\n"
             + "    调用 app_list_view(query=\"主题词\")，由工具读取最新 registry 后过滤。\n"
             + "  - 不要根据 prompt 或历史直接列出应用名称；应用清单是动态数据。\n\n"
             + "【边界】\n"
             + "  当用户的核心意图不是\"列出已安装应用\"，而是某个具体业务对象（由各 AIPP\n"
             + "  通过 prompt_contributions 声明），不要命中本工具，应交由对应 AIPP 的 skill。\n";
    }

    /**
     * 聚合 AAP-Pre（命中前规则）：
     * - {@code activeAppIds} 为空（或 null）→ 包含所有 working app；
     * - 非空 → 仅包含活跃 app（避免无关 AIPP 的领域 prompt 污染当前 session）。
     */
    public String aggregatedPrePrompt(Set<String> activeAppIds) {
        refreshMissingAppsIfNeeded();
        StringBuilder sb = new StringBuilder();
        LinkedHashSet<String> orderedAppIds = new LinkedHashSet<>();
        boolean hasActive = activeAppIds != null && !activeAppIds.isEmpty();
        if (hasActive) {
            for (String appId : activeAppIds) {
                if (appId != null && !appId.isBlank() && registry.containsKey(appId)) {
                    orderedAppIds.add(appId);
                }
            }
        } else {
            orderedAppIds.addAll(registry.keySet());
        }
        if (orderedAppIds.isEmpty()) return "";

        for (String appId : orderedAppIds) {
            AppRegistration app = registry.get(appId);
            if (app == null) continue;
            List<String> promptParts = new ArrayList<>();
            if (app.systemPromptContribution() != null && !app.systemPromptContribution().isBlank()) {
                promptParts.add(app.systemPromptContribution().strip());
            }
            List<Map<String, Object>> contributions = app.promptContributions() != null
                    ? app.promptContributions() : List.of();
            contributions.stream()
                    .filter(this::isKnownContributionLayer)
                    .filter(AppRegistry::isPreContribution)
                    .sorted(Comparator.comparingInt(AppRegistry::contributionPriority).reversed())
                    .map(AppRegistry::contributionContent)
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::strip)
                    .forEach(promptParts::add);
            if (promptParts.isEmpty()) continue;
            sb.append("# ").append(app.name()).append(" (AAP-Pre)\n");
            sb.append(String.join("\n\n", promptParts)).append("\n\n");
        }
        return sb.toString();
    }

    /** 聚合当前 active app 的 system prompt（Host + AAP-Pre）。 */
    public String aggregatedSystemPrompt(Set<String> activeAppIds) {
        refreshMissingAppsIfNeeded();
        StringBuilder sb = new StringBuilder();
        sb.append(hostSystemPrompt()).append("\n\n");
        sb.append(aggregatedPrePrompt(activeAppIds));
        return sb.toString();
    }

    /** 聚合所有 app（兼容旧接口，用于无上下文场景）。 */
    public String aggregatedSystemPrompt() {
        refreshMissingAppsIfNeeded();
        return aggregatedSystemPrompt(Set.of());
    }

    /** 通过 appId 获取展示名称（不存在时返回 appId）。 */
    public String appDisplayName(String appId) {
        if (appId == null || appId.isBlank()) return "unknown-app";
        AppRegistration reg = registry.get(appId);
        if (reg == null || reg.name() == null || reg.name().isBlank()) return appId;
        return reg.name();
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
     * Phase 3：从 {@code /api/tools} 返回的 skills 列表中提取带 widget-level scope 的工具，
     * 直接填充 {@link #toolIndex} 与 {@link #widgetCanvasToolsIndex}。
     *
     * <p>返回已从 {@code /api/tools} 贡献了 canvas 工具的 widget type 集合 —— 之后遍历
     * widget manifest 时若该 widgetType 已在集合里，则跳过 {@code canvas_skill.tools} 读取
     * （{@code /api/tools} 为权威来源；widget manifest 同名字段作为遗留/回退）。
     */
    @SuppressWarnings("unchecked")
    private Set<String> indexWidgetScopedFromTools(List<Map<String, Object>> skills, AppRegistration reg) {
        Set<String> widgetsWithCanvasTools = new HashSet<>();
        Map<String, List<Map<String, Object>>> perWidget = new LinkedHashMap<>();
        for (Map<String, Object> skill : skills) {
            Object scopeObj = skill.get("scope");
            if (!(scopeObj instanceof Map<?, ?> sMap)) continue;
            Map<String, Object> scope = (Map<String, Object>) sMap;
            if (!"widget".equals(scope.get("level"))) continue;
            Object ownerWidget = scope.get("owner_widget");
            if (ownerWidget == null) continue;
            String wt = ownerWidget.toString();

            Object visObj = skill.get("visibility");
            List<?> vis = (visObj instanceof List<?>) ? (List<?>) visObj : List.of();

            if (vis.contains("ui")) {
                Object name = skill.get("name");
                if (name != null) {
                    toolIndex.put(name.toString(), reg);
                    log.debug("Registered widget-scoped UI tool from /api/tools: {} → {}", name, wt);
                }
            }

            boolean llmCanvas = vis.contains("llm")
                && ("canvas_open".equals(scope.get("visible_when"))
                    || scope.get("visible_when") == null);
            if (llmCanvas) {
                Map<String, Object> toolDef = new LinkedHashMap<>(skill);
                toolDef.remove("visibility");
                toolDef.remove("scope");
                perWidget.computeIfAbsent(wt, k -> new ArrayList<>()).add(toolDef);
                widgetsWithCanvasTools.add(wt);
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : perWidget.entrySet()) {
            widgetCanvasToolsIndex.put(e.getKey(), e.getValue());
            log.debug("Registered {} widget-scoped canvas tools from /api/tools for: {}",
                    e.getValue().size(), e.getKey());
        }
        return widgetsWithCanvasTools;
    }

    /**
     * 返回 widget 级 {@code scope} 对象（可能为 {@code null}）。
     * 结构：{@code {"tools_allow":[...],"tools_deny":[...],"forbid_execution":bool}}。
     */
    public Map<String, Object> getWidgetScope(String widgetType) {
        if (widgetType == null) return null;
        return widgetScopeIndex.get(widgetType);
    }

    /** 返回 widget 级 {@code system_prompt}（可能为 {@code null}）。 */
    public String getWidgetSystemPrompt(String widgetType) {
        if (widgetType == null) return null;
        return widgetSystemPromptIndex.get(widgetType);
    }

    /** 返回指定 view 的 scope（widget+view 双键查找；可能为 {@code null}）。 */
    public Map<String, Object> getWidgetViewScope(String widgetType, String viewId) {
        if (widgetType == null || viewId == null) return null;
        Map<String, Map<String, Object>> m = widgetViewScopeIndex.get(widgetType);
        return m == null ? null : m.get(viewId);
    }

    /** 返回指定 view 的 {@code system_prompt}（可能为 {@code null}）。 */
    public String getWidgetViewSystemPrompt(String widgetType, String viewId) {
        if (widgetType == null || viewId == null) return null;
        Map<String, String> m = widgetViewSystemPromptIndex.get(widgetType);
        return m == null ? null : m.get(viewId);
    }

    /**
     * 判定某 skill 是否属于"执行类"（受 widget/view {@code scope.forbid_execution} 约束）。
     *
     * <p>规则：显式 {@code kind=design} → 不是执行；其余（含缺省）一律按执行处理。
     */
    public boolean isSkillExecution(String skillName) {
        if (skillName == null) return true;
        String kind = skillKindIndex.get(skillName);
        return !"design".equalsIgnoreCase(kind);
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
     * 将 Host 级环境变量注入到调用参数（app 覆盖优先，全局兜底）。
     * 默认对调用方显式提供的同名参数不覆盖；
     * 但 env 作为运行环境策略变量，始终由 Host setting 覆盖。
     */
    public Map<String, Object> injectEnvVars(String appId, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>(args == null ? Map.of() : args);
        if (configStore == null) return out;
        Map<String, String> envVars = configStore.resolveEnvVarsForApp(appId);
        for (Map.Entry<String, String> e : envVars.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if ("env".equalsIgnoreCase(e.getKey())) {
                out.put("env", e.getValue());
            } else {
                out.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return out;
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

    // ── Host 解耦：output_widget_rules / lifecycle / event bus / runtime callback ──

    /** 返回 skill 的 output_widget_rules（可能为空 map）。 */
    public Map<String, Object> getOutputWidgetRules(String skillName) {
        if (skillName == null) return Map.of();
        return skillOutputWidgetRulesIndex.getOrDefault(skillName, Map.of());
    }

    /**
     * 判断给定工具响应是否触发"强制 canvas"规则（满足 force_canvas_when 全部字段非空）。
     * 静态方法以便直接在 GenericAgentLoop 中复用。
     */
    public static boolean matchesForceCanvas(com.fasterxml.jackson.databind.JsonNode root,
                                              Map<String, Object> rules) {
        if (root == null || rules == null || rules.isEmpty()) return false;
        Object fieldsObj = rules.get("force_canvas_when");
        if (!(fieldsObj instanceof List<?> fields) || fields.isEmpty()) return false;
        for (Object f : fields) {
            if (f == null) continue;
            String name = f.toString();
            com.fasterxml.jackson.databind.JsonNode v = root.path(name);
            if (v.isMissingNode() || v.isNull()) return false;
            if (v.isTextual() && v.asText("").isBlank()) return false;
        }
        return true;
    }

    /** 返回 skill 的 default_widget（{@code output_widget_rules.default_widget}），可能为 null。 */
    public String getDefaultWidget(String skillName) {
        Object dw = getOutputWidgetRules(skillName).get("default_widget");
        return dw == null ? null : dw.toString();
    }

    /**
     * 返回所有声明 {@code lifecycle == lifecycle} 的 skill 列表（[app, skill] pair）。
     * 兼容旧字段：{@code background:true} 视为等价 {@code lifecycle:post_turn}（仅当未显式声明时）。
     */
    public List<Map.Entry<AppRegistration, Map<String, Object>>> findSkillsByLifecycle(String lifecycle) {
        refreshMissingAppsIfNeeded();
        List<Map.Entry<AppRegistration, Map<String, Object>>> result = new ArrayList<>();
        for (AppRegistration app : registry.values()) {
            for (Map<String, Object> skill : app.skills()) {
                Object lc = skill.get("lifecycle");
                String resolved = lc == null ? null : lc.toString();
                if (resolved == null && Boolean.TRUE.equals(skill.get("background"))
                        && "post_turn".equals(lifecycle)) {
                    resolved = "post_turn";
                }
                if (lifecycle.equals(resolved)) {
                    result.add(Map.entry(app, skill));
                }
            }
        }
        return result;
    }

    /**
     * 通用事件总线：把指定事件 POST 到所有声明订阅该事件的 app 的 {@code /api/events}。
     * Fire-and-forget；失败仅记录 debug 日志。
     */
    public void publishEvent(String eventType, Map<String, Object> payload) {
        if (eventType == null || eventType.isBlank()) return;
        refreshMissingAppsIfNeeded();
        for (Map.Entry<String, Set<String>> e : appEventSubscriptionsIndex.entrySet()) {
            if (!e.getValue().contains(eventType)) continue;
            AppRegistration app = registry.get(e.getKey());
            if (app == null) continue;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("type", eventType);
                body.put("data", payload == null ? Map.of() : payload);
                String reqBody = JSON.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder(URI.create(app.baseUrl() + "/api/events"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                        .build();
                http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ex) {
                log.debug("publishEvent({}) to {} failed: {}", eventType, e.getKey(), ex.getMessage());
            }
        }
    }

    /**
     * 返回 (app, path) 用于路由通用运行时事件，未注册时返回 null。
     * Path 中可能含 {@code {worldId}} 等占位符，由调用方替换。
     */
    public Map.Entry<AppRegistration, String> findCallbackForEvent(String eventName) {
        if (eventName == null) return null;
        refreshMissingAppsIfNeeded();
        return runtimeEventCallbackIndex.get(eventName);
    }

    /** 返回所有 LLM 可见 tool 的 display label 字典（用于 GET /api/tool-labels）。 */
    public Map<String, String> getAllToolDisplayLabels() {
        refreshMissingAppsIfNeeded();
        return new LinkedHashMap<>(toolDisplayLabelIndex);
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
        registerBuiltin(appId, name, baseUrl, systemPromptContribution, List.of(), skills, widgets);
    }

    /**
     * 注册 worldone 内置 app（含 prompt_contributions）。
     */
    public void registerBuiltin(String appId, String name, String baseUrl,
                                String systemPromptContribution,
                                List<Map<String, Object>> promptContributions,
                                List<Map<String, Object>> skills,
                                List<Map<String, Object>> widgets) {
        AppRegistration reg = new AppRegistration(appId, name, baseUrl,
                systemPromptContribution, promptContributions, skills, widgets);
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
            indexSkillKind(skill);
            indexHostDecouplingFields(skill, reg);
        }

        indexWidgetScopedFromTools(skills, reg);

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

            Object rendersFor = widget.get("renders_output_of_skill");
            if (type != null && rendersFor != null) {
                skillOutputWidgetIndex.put(rendersFor.toString(), type.toString());
            }

            indexWidgetViewFields(type, widget);
            indexWidgetAppIdentity(type, widget);
            indexWidgetContext(type, widget);
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

        JsonNode skillsRoot = fetchSkillsRoot(baseUrl);
        List<Map<String, Object>> skills  = fetchSkills(appId, skillsRoot);
        List<Map<String, Object>> widgets = fetchWidgets(appId, baseUrl);
        String systemPrompt = fetchSystemPrompt(skillsRoot);
        List<Map<String, Object>> promptContributions = fetchPromptContributions(skillsRoot);
        String name = manifest.path("name").asText(appId);

        AppRegistration reg = new AppRegistration(appId, name, baseUrl, systemPrompt, promptContributions, skills, widgets);
        registry.put(appId, reg);
        appLoadErrorIndex.remove(appId);

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
            indexSkillKind(skill);
            indexHostDecouplingFields(skill, reg);
        }

        indexAppLevelDecoupling(skillsRoot, reg);
        indexWidgetScopedFromTools(skills, reg);

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

            // Phase 5b：widget manifest 不再携带 canvas_skill / internal_tools；
            // 这些 widget 级 tool 一律由 /api/tools 通过 indexWidgetScopedFromTools() 填充。

            // skill_name → widget_type（skill 执行后自动渲染输出）
            Object rendersFor = widget.get("renders_output_of_skill");
            if (type != null && rendersFor != null) {
                skillOutputWidgetIndex.put(rendersFor.toString(), type.toString());
                log.debug("Registered skill-output-widget: skill={} → widget={}", rendersFor, type);
            }

            // views / refresh_skill / mutating_tools（AIPP Widget View 协议）
            indexWidgetViewFields(type, widget);

            // app_id / is_main / is_canvas_mode（AIPP App Identity 协议）
            indexWidgetAppIdentity(type, widget);

            // session.mode / inherit / scope / system_prompt（Widget Session 协议）
            indexWidgetContext(type, widget);
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

        Object appId = widget.get("app_id");
        if (appId != null && !appId.toString().isBlank()) {
            widgetAppOwnerIndex.put(wt, appId.toString());
        }

        Object isMain = widget.get("is_main");
        if (Boolean.TRUE.equals(isMain)) {
            if (appId != null && !appId.toString().isBlank()) {
                appMainWidgetIndex.put(appId.toString(), wt);
                log.debug("Registered main widget for app {}: {}", appId, wt);
            }
        }
    }

    /** 索引 skill 的 {@code kind} 字段（design / execution），供 dedicated widget session 过滤使用。 */
    private void indexSkillKind(Map<String, Object> skill) {
        Object name = skill.get("name");
        if (name == null || name.toString().isBlank()) return;
        Object kind = skill.get("kind");
        if (kind != null && !kind.toString().isBlank()) {
            skillKindIndex.put(name.toString(), kind.toString());
        }
    }

    /**
     * 索引 Host 解耦协议字段：
     * {@code output_widget_rules} / {@code lifecycle} / {@code runtime_event_callback} /
     * {@code display_label_zh}（或 {@code display_name}）。
     */
    @SuppressWarnings("unchecked")
    private void indexHostDecouplingFields(Map<String, Object> skill, AppRegistration reg) {
        Object nameObj = skill.get("name");
        if (nameObj == null) return;
        String name = nameObj.toString();

        Object rules = skill.get("output_widget_rules");
        if (rules instanceof Map<?, ?> rMap) {
            skillOutputWidgetRulesIndex.put(name, (Map<String, Object>) rMap);
            Object dw = ((Map<String, Object>) rMap).get("default_widget");
            if (dw != null && !dw.toString().isBlank()) {
                skillOutputWidgetIndex.putIfAbsent(name, dw.toString());
            }
        }

        Object lc = skill.get("lifecycle");
        if (lc != null && !lc.toString().isBlank()) {
            skillLifecycleIndex.put(name, lc.toString());
        }

        Object cb = skill.get("runtime_event_callback");
        if (cb instanceof Map<?, ?> cMap) {
            Object events = ((Map<String, Object>) cMap).get("events");
            Object path   = ((Map<String, Object>) cMap).get("path");
            if (events instanceof List<?> evList && path != null && !path.toString().isBlank()) {
                for (Object ev : evList) {
                    if (ev == null || ev.toString().isBlank()) continue;
                    runtimeEventCallbackIndex.put(ev.toString(), Map.entry(reg, path.toString()));
                }
            }
        }

        Object label = skill.get("display_label_zh");
        if (label == null || label.toString().isBlank()) label = skill.get("display_name");
        if (label != null && !label.toString().isBlank()) {
            toolDisplayLabelIndex.put(name, label.toString());
        }
    }

    /**
     * 索引 app 级 {@code event_subscriptions} 与 app 级 {@code runtime_event_callback}（来自
     * {@code /api/tools} 顶层），供事件总线和运行时回调路由。
     */
    @SuppressWarnings("unchecked")
    private void indexAppLevelDecoupling(JsonNode toolsRoot, AppRegistration reg) {
        if (toolsRoot == null) return;
        JsonNode subs = toolsRoot.path("event_subscriptions");
        if (subs.isArray()) {
            Set<String> set = ConcurrentHashMap.newKeySet();
            for (JsonNode s : subs) if (s.isTextual() && !s.asText().isBlank()) set.add(s.asText());
            if (!set.isEmpty()) appEventSubscriptionsIndex.put(reg.appId(), set);
        }
        // 支持单对象或数组；数组形态 {@code runtime_event_callbacks} 允许多事件多路径。
        indexCallbackNode(toolsRoot.path("runtime_event_callback"), reg);
        JsonNode plural = toolsRoot.path("runtime_event_callbacks");
        if (plural.isArray()) {
            for (JsonNode n : plural) indexCallbackNode(n, reg);
        }
    }

    private void indexCallbackNode(JsonNode cb, AppRegistration reg) {
        if (cb == null || !cb.isObject()) return;
        JsonNode events = cb.path("events");
        String path = cb.path("path").asText("");
        if (!events.isArray() || path.isBlank()) return;
        for (JsonNode e : events) {
            if (e.isTextual() && !e.asText().isBlank()) {
                runtimeEventCallbackIndex.put(e.asText(), Map.entry(reg, path));
            }
        }
    }

    /**
     * 索引 widget manifest 中的 {@code system_prompt} / {@code scope} / {@code views[]}
     * 字段（Widget Context & Scope 协议，{@code aipp-protocol.md} § 3.2.1）。
     */
    @SuppressWarnings("unchecked")
    private void indexWidgetContext(Object type, Map<String, Object> widget) {
        if (type == null) return;
        String wt = type.toString();

        Object sp = widget.get("system_prompt");
        if (sp != null && !sp.toString().isBlank()) {
            widgetSystemPromptIndex.put(wt, sp.toString());
        }
        Object scope = widget.get("scope");
        if (scope instanceof Map<?, ?> sMap) {
            widgetScopeIndex.put(wt, (Map<String, Object>) sMap);
        }
        Object views = widget.get("views");
        if (views instanceof List<?> vList) {
            Map<String, Map<String, Object>> viewScopes = new ConcurrentHashMap<>();
            Map<String, String> viewPrompts = new ConcurrentHashMap<>();
            for (Object v : vList) {
                if (!(v instanceof Map<?, ?> vMap)) continue;
                Object vid = vMap.get("id");
                if (vid == null || vid.toString().isBlank()) continue;
                Object vsp = vMap.get("system_prompt");
                if (vsp != null && !vsp.toString().isBlank()) {
                    viewPrompts.put(vid.toString(), vsp.toString());
                }
                Object vscope = vMap.get("scope");
                if (vscope instanceof Map<?, ?> vsMap) {
                    viewScopes.put(vid.toString(), (Map<String, Object>) vsMap);
                }
            }
            if (!viewScopes.isEmpty()) widgetViewScopeIndex.put(wt, viewScopes);
            if (!viewPrompts.isEmpty()) widgetViewSystemPromptIndex.put(wt, viewPrompts);
        }
    }

    /** 根据 widget_type 反查所属 app_id。 */
    public String getWidgetOwnerAppId(String widgetType) {
        refreshMissingAppsIfNeeded();
        if (widgetType == null || widgetType.isBlank()) return null;
        return widgetAppOwnerIndex.get(widgetType);
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
        Map<String, Map<String, Object>> resultByApp = new LinkedHashMap<>();
        for (AppRegistration reg : registry.values()) {
            Map<String, Object> m = appManifestIndex.getOrDefault(reg.appId(), null);
            if (m != null) {
                // 追加 main_widget_type 供前端直接使用
                Map<String, Object> enriched = new LinkedHashMap<>(m);
                String mainWidget = appMainWidgetIndex.get(reg.appId());
                if (mainWidget != null) enriched.put("main_widget_type", mainWidget);
                boolean online = isAppOnline(reg);
                boolean active = !Boolean.FALSE.equals(enriched.get("is_active"));
                enriched.put("is_active", active && online);
                enriched.put("load_ok", online);
                enriched.put("load_error", online ? "" : appLoadErrorIndex.getOrDefault(
                        reg.appId(), "当前无法连接应用服务，请确认应用已启动且可访问"));
                resultByApp.put(reg.appId(), enriched);
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
                boolean online = isAppOnline(reg);
                min.put("is_active", online);
                min.put("load_ok", online);
                min.put("load_error", online ? "" : appLoadErrorIndex.getOrDefault(
                        reg.appId(), "当前无法连接应用服务，请确认应用已启动且可访问"));
                resultByApp.put(reg.appId(), min);
            }
        }

        // 追加“已安装但当前加载失败”的 app，保证前端可以灰显展示并给出告警。
        if (Files.exists(APPS_ROOT)) {
            File[] appDirs = APPS_ROOT.toFile().listFiles(File::isDirectory);
            if (appDirs != null) {
                for (File appDir : appDirs) {
                    Path manifestPath = appDir.toPath().resolve("manifest.json");
                    if (!Files.exists(manifestPath)) continue;
                    try {
                        JsonNode manifest = JSON.readTree(Files.readString(manifestPath));
                        String appId = manifest.path("id").asText(appDir.getName());
                        if (resultByApp.containsKey(appId)) continue;

                        Map<String, Object> min = new LinkedHashMap<>();
                        min.put("app_id", appId);
                        min.put("app_name", manifest.path("app_name").asText(
                                manifest.path("name").asText(appId)));
                        min.put("app_icon", manifest.path("app_icon").asText(""));
                        min.put("app_description", manifest.path("app_description").asText(
                                manifest.path("description").asText("应用加载失败，请检查服务状态")));
                        min.put("app_color", manifest.path("app_color").asText("#6b7a9e"));
                        min.put("is_active", false);
                        min.put("version", manifest.path("version").asText(""));
                        String mainWidget = manifest.path("main_widget_type").asText("");
                        if (!mainWidget.isBlank()) min.put("main_widget_type", mainWidget);
                        min.put("load_ok", false);
                        min.put("load_error", appLoadErrorIndex.getOrDefault(
                                appId, "当前无法连接应用服务，请确认应用已启动且可访问"));
                        resultByApp.put(appId, min);
                    } catch (Exception ignored) {
                        // skip malformed manifest
                    }
                }
            }
        }
        return new ArrayList<>(resultByApp.values());
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

    /**
     * Phase 4：原子 Tool 清单唯一权威端点为 {@code /api/tools}。
     * {@code /api/skills} 自 Phase 4 起专用于 Skill Playbook 索引，不再承载 Tool 定义。
     */
    private JsonNode fetchSkillsRoot(String baseUrl) throws Exception {
        return JSON.readTree(get(baseUrl + "/api/tools"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchSkills(String appId, JsonNode root) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode list = root.path("tools");
        for (JsonNode skill : list) {
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

    private String fetchSystemPrompt(JsonNode root) {
        return root.path("system_prompt").asText("");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPromptContributions(JsonNode root) {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            JsonNode node = root.path("prompt_contributions");
            if (!node.isArray()) return List.of();
            for (JsonNode c : node) {
                result.add(JSON.treeToValue(c, Map.class));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String contributionContent(Map<String, Object> c) {
        Object content = c.get("content");
        if (content instanceof String s) return s;
        Object prompt = c.get("prompt");
        if (prompt instanceof String s) return s;
        Object text = c.get("text");
        if (text instanceof String s) return s;
        return null;
    }

    private static int contributionPriority(Map<String, Object> c) {
        Object p = c.get("priority");
        if (p instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(p)); } catch (Exception ignored) { return 0; }
    }

    private static boolean isPreContribution(Map<String, Object> c) {
        return "aap_pre".equals(norm(c.get("layer")));
    }

    private static boolean isPostContribution(Map<String, Object> c) {
        return "aap_post".equals(norm(c.get("layer")));
    }

    private boolean isKnownContributionLayer(Map<String, Object> c) {
        String layer = norm(c.get("layer"));
        if ("aap_pre".equals(layer) || "aap_post".equals(layer)) return true;
        String id = Objects.toString(c.get("id"), "(no-id)");
        String warnKey = id + "|" + layer;
        if (invalidContributionLayerWarned.add(warnKey)) {
            log.warn("Ignoring prompt_contribution without valid layer (expected aap_pre|aap_post): id={}", id);
        }
        return false;
    }

    private static String norm(Object v) {
        return v == null ? "" : v.toString().trim().toLowerCase(Locale.ROOT);
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
                    String err = e.getMessage();
                    if (err == null || err.isBlank()) err = e.getClass().getSimpleName();
                    appLoadErrorIndex.put(dirName, err);
                    log.debug("Runtime app refresh skipped {}: {}", dirName, e.getMessage());
                }
            }
        }
    }

    /** 返回 app 当前在线状态（按固定间隔探测 /api/tools）。 */
    private boolean isAppOnline(AppRegistration reg) {
        String appId = reg.appId();
        long now = System.currentTimeMillis();
        Long lastChecked = appOnlineCheckedAtMs.get(appId);
        if (lastChecked != null && now - lastChecked < APP_ONLINE_CHECK_INTERVAL_MS) {
            return appOnlineIndex.getOrDefault(appId, true);
        }
        boolean online;
        try {
            get(reg.baseUrl() + "/api/tools");
            online = true;
            appLoadErrorIndex.remove(appId);
        } catch (Exception e) {
            online = false;
            String err = e.getMessage();
            if (err == null || err.isBlank()) err = e.getClass().getSimpleName();
            appLoadErrorIndex.put(appId, err);
        }
        appOnlineIndex.put(appId, online);
        appOnlineCheckedAtMs.put(appId, now);
        return online;
    }
}
