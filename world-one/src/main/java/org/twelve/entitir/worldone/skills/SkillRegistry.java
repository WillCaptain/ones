package org.twelve.entitir.worldone.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.twelve.entitir.worldone.AppRegistration;
import org.twelve.entitir.worldone.AppRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 索引中心 — progressive disclosure 的注册侧。
 *
 * <p>启动时（以及运行期惰性补刷）对每个已注册 AIPP 应用尝试拉取 {@code /api/skills}
 * （Phase 4 之后该端点专门承载 Skill Playbook 索引）；端点不存在 / 返回空数组则
 * 静默跳过，维持兼容 —— 尚未定义 playbook 的 app 不影响 host 启动。
 *
 * <p>Anthropic Skills 风格（2025-10 spec）：只承载 skill 索引（{@code name + description +
 * allowed_tools + 位置 scope}）和 playbook 懒加载缓存；召回由 <b>主 LLM 自己完成</b>
 * （{@code GenericAgentLoop} 把 {@link #visibleSkills} 的结果注入 system prompt，
 * 主 LLM 通过 {@code load_skill} meta-tool 激活），本类不做关键词匹配也不维护独立 Loop A。
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private AppRegistry apps;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** appId → 已拉取的 skill 定义列表。 */
    private final Map<String, List<SkillDefinition>> indexByApp = new ConcurrentHashMap<>();

    /** (appId, skillId) → 已缓存 playbook 文本。 */
    private final Map<String, String> playbookCache = new ConcurrentHashMap<>();
    public static final String AIPP_ENTRY_PLAYBOOK = "__aipp_entry__";

    // ── 索引加载 ──────────────────────────────────────────────────────────────

    /**
     * 尝试为指定 app 拉取 {@code /api/skills}（Phase 4：Skill Playbook 索引端点）。
     * 静默失败（对应 app 未实现 skill 协议或返回空索引）。
     */
    public void refreshAppIndex(String appId) {
        AppRegistration app = findApp(appId);
        if (app == null) return;
        try {
            String body = httpGet(app.baseUrl() + "/api/skills");
            List<SkillDefinition> defs = parseIndex(appId, body);
            indexByApp.put(appId, defs);
            log.info("[SkillRegistry] loaded {} skills for app={}", defs.size(), appId);
        } catch (Exception e) {
            indexByApp.remove(appId);
            log.debug("[SkillRegistry] no skill index for app={}: {}", appId, e.getMessage());
        }
    }

    /** 对所有已注册 app 尝试刷新。可在运行期触发（如插件热加载）。 */
    public void refreshAll() {
        for (AppRegistration app : apps.apps()) {
            refreshAppIndex(app.appId());
        }
    }

    /** Spring 启动就绪后首次拉取。app 未实现 /api/skills 索引时静默跳过。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refreshAll();
    }

    /**
     * 解析 Anthropic-风格的 skill 索引。旧字段 {@code id / triggers / embedding_hint} 已去除，
     * 兼容性读取：若条目仍使用 {@code id}，将其作为 {@code name} 的兜底来源。
     */
    private List<SkillDefinition> parseIndex(String appId, String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        JsonNode arr = root.isArray() ? root : root.path("skills");
        if (!arr.isArray()) return List.of();
        List<SkillDefinition> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String name = n.path("name").asText(null);
            if (name == null || name.isBlank()) {
                // 兼容旧索引可能仍回传 id
                name = n.path("id").asText(null);
            }
            if (name == null || name.isBlank()) continue;

            String level       = n.path("level").asText(SkillDefinition.LEVEL_APP);
            String desc        = n.path("description").asText("");
            String ownerWidget = n.path("owner_widget").asText(null);
            String ownerView   = n.path("owner_view").asText(null);
            String playbookUrl = n.path("playbook_url")
                    .asText("/api/skills/" + name + "/playbook");
            List<String> allowed = toStringList(n.path("allowed_tools"));

            out.add(new SkillDefinition(
                    name, appId, level,
                    blankToNull(ownerWidget), blankToNull(ownerView),
                    desc, allowed, playbookUrl));
        }
        return out;
    }

    // ── 查询 ──────────────────────────────────────────────────────────────────

    /** 返回所有 skill（跨 app）。 */
    public List<SkillDefinition> allSkills() {
        List<SkillDefinition> out = new ArrayList<>();
        for (List<SkillDefinition> list : indexByApp.values()) out.addAll(list);
        return out;
    }

    /** 按 level 过滤。 */
    public List<SkillDefinition> skillsByLevel(String level) {
        List<SkillDefinition> out = new ArrayList<>();
        for (SkillDefinition s : allSkills()) {
            if (Objects.equals(s.level(), level)) out.add(s);
        }
        return out;
    }

    /**
     * Host root router 只看这些 AIPP entry skills：一个 app 一个入口。
     * 真正命中 app 后，下一层 Router 才展开该 app 自己的 skill catalog。
     */
    public List<SkillDefinition> appEntrySkills() {
        List<SkillDefinition> out = new ArrayList<>();
        for (AppRegistration app : apps.apps()) {
            if ("worldone-system".equals(app.appId())) continue;
            if ((app.widgets() == null || app.widgets().isEmpty())
                    && (app.skills() == null || app.skills().isEmpty())) continue;
            out.add(new SkillDefinition(
                    "aipp_" + app.appId().replaceAll("[^A-Za-z0-9_]", "_"),
                    app.appId(),
                    "aipp_entry",
                    null,
                    null,
                    appEntryDescription(app),
                    List.of(),
                    AIPP_ENTRY_PLAYBOOK));
        }
        return out;
    }

    private static String appEntryDescription(AppRegistration app) {
        List<String> parts = new ArrayList<>();
        parts.add("Route to AIPP app `" + app.appId() + "` (" + app.name() + ").");
        if (app.systemPromptContribution() != null && !app.systemPromptContribution().isBlank()) {
            parts.add(app.systemPromptContribution().strip());
        }
        if (app.promptContributions() != null) {
            app.promptContributions().stream()
                    .map(SkillRegistry::contributionContent)
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::strip)
                    .forEach(parts::add);
        }
        String desc = String.join(" ", parts).replaceAll("\\s+", " ").trim();
        return desc.length() <= 1000 ? desc : desc.substring(0, 997) + "...";
    }

    private static String contributionContent(Map<String, Object> contribution) {
        Object c = contribution == null ? null : contribution.get("content");
        return c == null ? "" : c.toString();
    }

    /** 查询某个 app 下的 skill。 */
    public List<SkillDefinition> skillsByApp(String appId) {
        return List.copyOf(indexByApp.getOrDefault(appId, List.of()));
    }

    /** 按 name 定位。返回 Optional 避免 NPE。 */
    public Optional<SkillDefinition> find(String appId, String skillName) {
        for (SkillDefinition s : indexByApp.getOrDefault(appId, List.of())) {
            if (Objects.equals(s.name(), skillName)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * 跨所有 app 按 name 查找一个 skill（当前位置可见的那个）。
     * 同名冲突时取第一个命中 —— Anthropic 规范要求 name 在生态内唯一，冲突视作数据问题。
     */
    public Optional<SkillDefinition> findAnywhere(String skillName) {
        for (List<SkillDefinition> list : indexByApp.values()) {
            for (SkillDefinition s : list) {
                if (Objects.equals(s.name(), skillName)) return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回当前 UI 位置下对主 LLM 可见的 skill 集合（用于注入 system prompt 的 Available Skills 段）。
     *
     * <p>可见性规则（比召回更宽松，只做"位置过滤"，语义判断交给主 LLM）：
     * <ul>
     *   <li>{@code level=universal}：任何位置都可见</li>
     *   <li>{@code level=app}：{@code activeAppIds} 为空（纯 chat）或包含该 app</li>
     *   <li>{@code level=widget}：当前在 widget 内且 {@code owner_widget} 匹配</li>
     *   <li>{@code level=view}：当前在 view 内且 {@code owner_widget} + {@code owner_view} 都匹配</li>
     * </ul>
     * 这是 Anthropic 扁平 catalog 的"位置分层扩展"，目的是让 skill 数量大时 catalog 不爆炸。
     */
    public List<SkillDefinition> visibleSkills(
            String widgetType, String viewId, Set<String> activeAppIds) {
        Set<String> apps = activeAppIds == null ? Set.of() : activeAppIds;
        List<SkillDefinition> out = new ArrayList<>();
        for (SkillDefinition s : allSkills()) {
            if (isVisibleAt(s, widgetType, viewId, apps)) out.add(s);
        }
        return out;
    }

    private static boolean isVisibleAt(
            SkillDefinition s, String widgetType, String viewId, Set<String> activeAppIds) {
        String lvl = s.level();
        if (SkillDefinition.LEVEL_UNIVERSAL.equals(lvl)) return true;
        if (SkillDefinition.LEVEL_APP.equals(lvl)) {
            return activeAppIds.isEmpty() || activeAppIds.contains(s.appId());
        }
        if (SkillDefinition.LEVEL_WIDGET.equals(lvl)) {
            return widgetType != null && Objects.equals(s.ownerWidget(), widgetType);
        }
        if (SkillDefinition.LEVEL_VIEW.equals(lvl)) {
            return widgetType != null && viewId != null
                    && Objects.equals(s.ownerWidget(), widgetType)
                    && Objects.equals(s.ownerView(), viewId);
        }
        return false;
    }

    // ── Playbook 加载 ─────────────────────────────────────────────────────────

    /**
     * 拉取并缓存 SKILL.md 正文。优先读 {@code playbookUrl}，缺省回退到
     * {@code /api/skills/{id}/playbook}。
     */
    public String loadPlaybook(SkillDefinition skill) {
        String cacheKey = skill.appId() + "::" + skill.name();
        String cached = playbookCache.get(cacheKey);
        if (cached != null) return cached;

        AppRegistration app = findApp(skill.appId());
        if (app == null) return "";
        String url = app.baseUrl() + (skill.playbookUrl() != null && !skill.playbookUrl().isBlank()
                ? skill.playbookUrl()
                : "/api/skills/" + skill.name() + "/playbook");
        try {
            String body = httpGet(url);
            playbookCache.put(cacheKey, body);
            return body;
        } catch (Exception e) {
            log.warn("[SkillRegistry] failed to load playbook {}: {}", url, e.getMessage());
            return "";
        }
    }

    /** 清空 playbook 缓存（开发/热加载场景使用）。 */
    public void clearPlaybookCache() {
        playbookCache.clear();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private AppRegistration findApp(String appId) {
        for (AppRegistration app : apps.apps()) {
            if (Objects.equals(app.appId(), appId)) return app;
        }
        return null;
    }

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + url);
        }
        return resp.body();
    }

    private static List<String> toStringList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode e : n) {
            String s = e.asText("");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
