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
 * <p>当前仅承载索引与 playbook 文本缓存；召回逻辑在 {@link SkillRecaller}，
 * 执行逻辑在 {@link SkillExecutor}。
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

    @SuppressWarnings("unchecked")
    private List<SkillDefinition> parseIndex(String appId, String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        JsonNode arr = root.isArray() ? root : root.path("skills");
        if (!arr.isArray()) return List.of();
        List<SkillDefinition> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String id    = n.path("id").asText(null);
            if (id == null || id.isBlank()) continue;
            String level = n.path("level").asText(SkillDefinition.LEVEL_APP);
            String name  = n.path("name").asText(id);
            String desc  = n.path("description").asText("");
            String hint  = n.path("embedding_hint").asText("");
            String ownerWidget = n.path("owner_widget").asText(null);
            String ownerView   = n.path("owner_view").asText(null);
            String playbookUrl = n.path("playbook_url").asText("/api/skills/" + id + "/playbook");

            List<String> triggers = toStringList(n.path("triggers"));
            List<String> tools    = toStringList(n.path("tools"));

            out.add(new SkillDefinition(id, appId, level,
                    blankToNull(ownerWidget), blankToNull(ownerView),
                    name, desc, triggers, hint, tools, playbookUrl));
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

    /** 查询某个 app 下的 skill。 */
    public List<SkillDefinition> skillsByApp(String appId) {
        return List.copyOf(indexByApp.getOrDefault(appId, List.of()));
    }

    /** 按 id 定位。返回 Optional 避免 NPE。 */
    public Optional<SkillDefinition> find(String appId, String skillId) {
        for (SkillDefinition s : indexByApp.getOrDefault(appId, List.of())) {
            if (Objects.equals(s.id(), skillId)) return Optional.of(s);
        }
        return Optional.empty();
    }

    // ── Playbook 加载 ─────────────────────────────────────────────────────────

    /**
     * 拉取并缓存 SKILL.md 正文。优先读 {@code playbookUrl}，缺省回退到
     * {@code /api/skills/{id}/playbook}。
     */
    public String loadPlaybook(SkillDefinition skill) {
        String cacheKey = skill.appId() + "::" + skill.id();
        String cached = playbookCache.get(cacheKey);
        if (cached != null) return cached;

        AppRegistration app = findApp(skill.appId());
        if (app == null) return "";
        String url = app.baseUrl() + (skill.playbookUrl() != null && !skill.playbookUrl().isBlank()
                ? skill.playbookUrl()
                : "/api/skills/" + skill.id() + "/playbook");
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
