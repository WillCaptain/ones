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
        Paths.get(System.getProperty("user.home"), ".ones", "apps");

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

    /** Host 全局规则（不含具体 AIPP 业务规则，但包含本域 app_list_view 的路由）。 */
    public String hostSystemPrompt() {
        return """
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
            对话历史的解读规则
            ════════════════════════════════════════
            对话历史仅是"参考上下文"，不代表工具已被调用或操作已完成。
            即使历史记录中出现过"已删除/已创建/已完成"等文字，
            也不能证明对应工具实际上被调用过。
            用户每次发出操作指令都必须重新调用相应工具——不能基于历史假设操作已完成。

            ════════════════════════════════════════
            查看/列表类操作规则（html_widget 面板）
            ════════════════════════════════════════
            "列出/查看/显示 …… 列表"等查看类请求，每次都必须重新调用工具，
            获取最新数据展示给用户——不管历史中是否有过类似的操作记录。
            这类操作没有副作用，重复调用只会刷新展示，不会产生重复数据。

            ════════════════════════════════════════
            Session 判断规范
            ════════════════════════════════════════
            - 当前对话历史中已有某个 session_id 时，优先复用，不要重复创建新 session。
            - 用户说"继续"、"接着做"、"edit XX"、"进入XX"等意图时，
              先检查历史中是否已有匹配的 session_id，有则直接使用。
            """ + buildAppDomainSection();
    }

    /**
     * Host 自有域：应用（AIPP app / 插件 / 功能模块）。
     *
     * <p>本段是 host 对 {@code app_list_view} 的 <b>路由声明 + 参数抽取规则 + 清单</b>。
     * 与 world-entitir 的 world 域声明完全对称，LLM 看到对称结构后能更稳定地做选择。
     */
    private String buildAppDomainSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n════════════════════════════════════════\n")
          .append("宿主域：应用（app / 插件 / 功能模块）\n")
          .append("════════════════════════════════════════\n")
          .append("【命中本域的触发词（必须是用户明说）】\n")
          .append("  应用 / app / 插件 / 功能模块 / 已安装了哪些\n")
          .append("  → 调用 `app_list_view`\n\n")
          .append("【不属于本域，绝不命中 app_list_view】\n")
          .append("  - 世界 / 本体 / 本体世界 / ontology —— 交由 world 域\n")
          .append("  - 记忆 / 会话 / 业务流程（入职等）—— 交由对应 app\n")
          .append("  注：若用户说\"记忆相关应用\"，核心词是\"应用\"（记忆只是主题限定），\n")
          .append("      仍命中本工具，把\"记忆\"作为主题词在清单中语义匹配。\n\n")
          .append("【ids 参数抽取（强制）】\n")
          .append("  步骤 1：用户是否带主题/领域词？（\"记忆\"\"memory\"\"本体\"\"HR\" 等）\n")
          .append("    - 是 → 在【当前应用清单】里做语义匹配（同义词/中英文/近义领域），\n")
          .append("           把命中的 **真实 app_id** 作为 `ids` 数组传入\n")
          .append("    - 否 → 省略 `ids`（或传空数组），列出全部\n")
          .append("  正确示例：\n")
          .append("    用户：\"列出记忆相关应用\"\n");
        try {
            List<Map<String, Object>> apps = buildAppsManifests();
            List<Map<String, Object>> visible = apps.stream()
                .filter(a -> {
                    String id = String.valueOf(a.getOrDefault("app_id", ""));
                    return !"worldone".equals(id) && !"worldone-system".equals(id);
                })
                .toList();

            // 示例 app_id（优先用含"memory"或"记忆"的，找不到就用首个）
            String exampleId = visible.stream()
                .map(a -> String.valueOf(a.getOrDefault("app_id", "")))
                .filter(id -> id.toLowerCase().contains("memory") || id.contains("记忆"))
                .findFirst()
                .orElseGet(() -> visible.isEmpty()
                    ? "memory-one"
                    : String.valueOf(visible.get(0).getOrDefault("app_id", "memory-one")));

            sb.append("    调用：app_list_view(ids=[\"").append(exampleId).append("\"])\n\n")
              .append("    用户：\"列出所有应用\"\n")
              .append("    调用：app_list_view()   // 不传 ids\n\n");

            sb.append("【当前应用清单（随请求快照，ids 必须从此清单里选）】\n");
            if (visible.isEmpty()) {
                sb.append("  （当前无已注册应用）\n");
            } else {
                for (Map<String, Object> a : visible) {
                    String id   = String.valueOf(a.getOrDefault("app_id", ""));
                    String name = String.valueOf(a.getOrDefault("app_name", id));
                    String desc = String.valueOf(a.getOrDefault("app_description", ""));
                    sb.append("  - ").append(id).append(" — ").append(name);
                    if (desc != null && !desc.isBlank()) {
                        sb.append(" — ").append(desc);
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("    调用：app_list_view(ids=[\"memory-one\"])\n\n")
              .append("【当前应用清单】\n  （暂不可用：").append(e.getMessage()).append("）\n");
        }
        return sb.toString();
    }

    /**
     * 聚合 AAP-Pre（命中前规则）：
     * 1) 始终包含所有 working app；
     * 2) active app 仅提高优先级（排在前面），但不排他。
     */
    public String aggregatedPrePrompt(Set<String> activeAppIds) {
        refreshMissingAppsIfNeeded();
        StringBuilder sb = new StringBuilder();
        LinkedHashSet<String> orderedAppIds = new LinkedHashSet<>();
        if (activeAppIds != null) {
            for (String appId : activeAppIds) {
                if (appId != null && !appId.isBlank() && registry.containsKey(appId)) {
                    orderedAppIds.add(appId);
                }
            }
        }
        for (String appId : registry.keySet()) {
            orderedAppIds.add(appId);
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
                // Index inject_context per skill (AIPP protocol extension)
                Object ic = skill.get("inject_context");
                if (ic instanceof Map<?, ?> icMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> icTyped = (Map<String, Object>) icMap;
                    skillInjectContextIndex.put(toolName.toString(), icTyped);
                }
            }
            indexSkillKind(skill);
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
     * Phase 2：优先从 {@code /api/tools}（AIPP 新端点，payload 带 visibility/scope 元数据）
     * 读取；若 app 尚未升级到新端点，回退到 {@code /api/skills}。
     *
     * <p>返回的 JsonNode 根结构始终兼容老字段（{@code system_prompt} /
     * {@code prompt_contributions}），tool 列表位于 {@code tools} 或 {@code skills}
     * 字段之一，由 {@link #fetchSkills(String, JsonNode)} 统一抽取。
     */
    private JsonNode fetchSkillsRoot(String baseUrl) throws Exception {
        try {
            return JSON.readTree(get(baseUrl + "/api/tools"));
        } catch (Exception newEndpointMiss) {
            return JSON.readTree(get(baseUrl + "/api/skills"));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchSkills(String appId, JsonNode root) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        // Phase 2：优先读 "tools"（/api/tools 的字段名），回退到 "skills"（旧端点字段名）。
        JsonNode list = root.has("tools") ? root.path("tools") : root.path("skills");
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

    /** 返回 app 当前在线状态（按固定间隔探测 /api/tools，回退 /api/skills）。 */
    private boolean isAppOnline(AppRegistration reg) {
        String appId = reg.appId();
        long now = System.currentTimeMillis();
        Long lastChecked = appOnlineCheckedAtMs.get(appId);
        if (lastChecked != null && now - lastChecked < APP_ONLINE_CHECK_INTERVAL_MS) {
            return appOnlineIndex.getOrDefault(appId, true);
        }
        boolean online;
        try {
            try {
                get(reg.baseUrl() + "/api/tools");
            } catch (Exception newMiss) {
                get(reg.baseUrl() + "/api/skills");
            }
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
