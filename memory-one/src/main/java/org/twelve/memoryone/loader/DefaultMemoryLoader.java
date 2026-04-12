package org.twelve.memoryone.loader;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.twelve.memoryone.db.MemoryRepository;
import org.twelve.memoryone.model.*;
import org.twelve.memoryone.query.MemoryQuery;
import org.twelve.memoryone.store.JdbcMemoryStore;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Memory 加载器：按加载规则从数据库检索记忆，组装为可注入 LLM 的文本块。
 *
 * <p>RELATION type uses entity-anchored retrieval: entities mentioned in userMessage
 * are matched against subject_entity / object_entity columns for focused context.
 */
@Component
public class DefaultMemoryLoader {

    private static final int TOKEN_BUDGET_CHARS = 2400;

    @Autowired
    private JdbcMemoryStore store;

    @Autowired
    private MemoryRepository repo;

    public String load(String userId, String agentId, String sessionId, String workspaceId, String userMessage) {
        return loadWithIds(userId, agentId, sessionId, workspaceId, userMessage).injectionText();
    }

    /** Backward-compatible overload (no workspaceId — main session or unknown workspace). */
    public String load(String userId, String agentId, String sessionId, String userMessage) {
        return load(userId, agentId, sessionId, null, userMessage);
    }

    public MemoryLoadResult loadWithIds(String userId, String agentId, String sessionId, String workspaceId, String userMessage) {
        List<Memory> alwaysLoad  = loadAlways(userId, agentId, sessionId, workspaceId);
        List<Memory> conditional = loadConditional(userId, agentId, sessionId, workspaceId, userMessage);

        List<Memory> all = new ArrayList<>(alwaysLoad);
        for (Memory m : conditional) {
            if (!containsId(all, m.id())) all.add(m);
        }

        // Entity-anchored RELATION retrieval — augment with entity-specific relations
        if (userMessage != null && !userMessage.isBlank()) {
            List<Memory> entityRelations = loadEntityRelations(userId, agentId, sessionId, workspaceId, userMessage);
            for (Memory m : entityRelations) {
                if (!containsId(all, m.id())) all.add(m);
            }
        }

        if (all.isEmpty()) return MemoryLoadResult.EMPTY;

        store.recordAccess(all.stream().map(Memory::id).toList());

        return new MemoryLoadResult(format(all), all.stream().map(Memory::id).toList());
    }

    /**
     * 子 session（task/event）完整上下文。
     *
     * <p>组成 = GLOBAL(3类) + WORKSPACE(3类) + SESSION(5类)
     * <ul>
     *   <li>GLOBAL SEMANTIC/PROCEDURAL/RELATION：用户身份、全局约定、全局关系网络</li>
     *   <li>WORKSPACE SEMANTIC/PROCEDURAL/RELATION：当前任务/世界的领域知识（workspaceId 匹配）</li>
     *   <li>SESSION 全5类：当前 session 专属目标、事件、关系等</li>
     * </ul>
     */
    public MemoryLoadResult loadSessionContext(String userId, String agentId,
                                               String sessionId, String workspaceId,
                                               String userMessage) {
        List<Memory> all = new ArrayList<>();

        // ── GLOBAL 3类（用户全局知识，精确可引用条目）────────────────────────
        all.addAll(queryActive(userId, agentId, null, null, MemoryType.SEMANTIC,   MemoryScope.GLOBAL, 0.4f, 20));
        all.addAll(queryActive(userId, agentId, null, null, MemoryType.PROCEDURAL, MemoryScope.GLOBAL, 0f,   10));
        all.addAll(queryActive(userId, agentId, null, null, MemoryType.RELATION,   MemoryScope.GLOBAL, 0.5f, 10));

        // ── WORKSPACE 3类（当前任务/世界专属知识，多人协作共享）─────────────
        if (workspaceId != null && !workspaceId.isBlank()) {
            all.addAll(queryActive(userId, agentId, null, workspaceId, MemoryType.SEMANTIC,   MemoryScope.WORKSPACE, 0f, 20));
            all.addAll(queryActive(userId, agentId, null, workspaceId, MemoryType.PROCEDURAL, MemoryScope.WORKSPACE, 0f, 10));
            all.addAll(queryActive(userId, agentId, null, workspaceId, MemoryType.RELATION,   MemoryScope.WORKSPACE, 0f, 10));
        }

        // ── SESSION 全5类（调用方保证 sessionId != null）───────────────────
        all.addAll(queryActive(userId, agentId, sessionId, null, MemoryType.GOAL,       MemoryScope.SESSION, 0f,  5));
        all.addAll(queryActive(userId, agentId, sessionId, null, MemoryType.SEMANTIC,   MemoryScope.SESSION, 0f, 20));
        all.addAll(queryActive(userId, agentId, sessionId, null, MemoryType.PROCEDURAL, MemoryScope.SESSION, 0f, 10));
        all.addAll(queryActive(userId, agentId, sessionId, null, MemoryType.RELATION,   MemoryScope.SESSION, 0f, 10));
        all.addAll(topN(queryActive(userId, agentId, sessionId, null, MemoryType.EPISODIC, MemoryScope.SESSION, 0f, 30), 10));

        // ── entity-anchored 补充（与本轮 userMessage 相关的关系记忆）──────────
        if (userMessage != null && !userMessage.isBlank()) {
            List<Memory> entityRel = loadEntityRelations(userId, agentId, sessionId, workspaceId, userMessage);
            for (Memory m : entityRel) {
                if (!containsId(all, m.id())) all.add(m);
            }
        }

        if (all.isEmpty()) return MemoryLoadResult.EMPTY;
        store.recordAccess(all.stream().map(Memory::id).toList());
        return new MemoryLoadResult(formatSections(all), all.stream().map(Memory::id).toList());
    }

    /** Format for session context (global structural + workspace + session-specific). */
    private String formatSections(List<Memory> memories) {
        List<Memory> global    = memories.stream().filter(m -> m.scope() == MemoryScope.GLOBAL).toList();
        List<Memory> workspace = memories.stream().filter(m -> m.scope() == MemoryScope.WORKSPACE).toList();
        List<Memory> session   = memories.stream().filter(m -> m.scope() == MemoryScope.SESSION).toList();

        Map<MemoryType, List<Memory>> gByType = global.stream()
                .collect(java.util.stream.Collectors.groupingBy(Memory::type));
        Map<MemoryType, List<Memory>> wByType = workspace.stream()
                .collect(java.util.stream.Collectors.groupingBy(Memory::type));
        Map<MemoryType, List<Memory>> sByType = session.stream()
                .collect(java.util.stream.Collectors.groupingBy(Memory::type));

        StringBuilder sb = new StringBuilder();
        int budget = 1800;

        // GLOBAL 3类
        if (!global.isEmpty()) {
            sb.append("### 全局事实\n");
            budget = appendSection(sb, null, gByType.get(MemoryType.SEMANTIC),   budget);
            budget = appendSection(sb, null, gByType.get(MemoryType.PROCEDURAL), budget);
            budget = appendRelationSection(sb, gByType.get(MemoryType.RELATION), budget);
        }

        // WORKSPACE 3类
        if (!workspace.isEmpty()) {
            sb.append("### 任务知识\n");
            budget = appendSection(sb, null, wByType.get(MemoryType.SEMANTIC),   budget);
            budget = appendSection(sb, null, wByType.get(MemoryType.PROCEDURAL), budget);
            budget = appendRelationSection(sb, wByType.get(MemoryType.RELATION), budget);
        }

        // SESSION 5类
        if (!session.isEmpty()) {
            sb.append("### 会话上下文\n");
            budget = appendSection(sb, null, sByType.get(MemoryType.GOAL),       budget);
            budget = appendSection(sb, null, sByType.get(MemoryType.SEMANTIC),   budget);
            budget = appendSection(sb, null, sByType.get(MemoryType.PROCEDURAL), budget);
            budget = appendRelationSection(sb, sByType.get(MemoryType.RELATION), budget);
                     appendSection(sb, null, sByType.get(MemoryType.EPISODIC),   budget);
        }

        return sb.toString().trim();
    }

    public List<Memory> loadGoals(String userId, String agentId, String sessionId) {
        if (sessionId == null) return List.of();
        return queryActive(userId, agentId, sessionId, null, MemoryType.GOAL, MemoryScope.SESSION, 0f, 10);
    }

    /**
     * Entity-anchored RELATION retrieval.
     * Extracts potential entity names from userMessage by finding words that match
     * existing subject_entity or object_entity values, then loads all relations
     * involving those entities. Respects scope: only SESSION relations for the
     * current sessionId are included (GLOBAL/WORKSPACE always visible).
     */
    private List<Memory> loadEntityRelations(String userId, String agentId, String sessionId, String workspaceId, String userMessage) {
        Set<String> candidates = extractEntityCandidates(userMessage);
        if (candidates.isEmpty()) return List.of();

        Instant now = Instant.now();
        Set<UUID> seen = new LinkedHashSet<>();
        List<Memory> result = new ArrayList<>();

        for (String entity : candidates) {
            repo.findRelationsByEntity(agentId, userId, entity, sessionId, workspaceId, now)
                .stream()
                .map(this::fromEntity)
                .filter(m -> m != null && seen.add(m.id()))
                .limit(5)
                .forEach(result::add);
        }
        return result;
    }

    /**
     * Simple entity candidate extraction: split on whitespace and punctuation,
     * keep tokens ≥ 2 chars. The entity-matching in SQL is case-insensitive,
     * so this catches proper nouns, Chinese names, and English identifiers.
     */
    private Set<String> extractEntityCandidates(String text) {
        Set<String> out = new LinkedHashSet<>();
        // Split on whitespace + common punctuation
        for (String tok : text.split("[\\s,;:。，；：「」【】()（）]+")) {
            String t = tok.trim();
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    private Memory fromEntity(org.twelve.memoryone.db.MemoryEntity e) {
        return store.findById(UUID.fromString(e.getId())).orElse(null);
    }

    private List<Memory> loadAlways(String userId, String agentId, String sessionId, String workspaceId) {
        List<Memory> result = new ArrayList<>();
        result.addAll(queryActive(userId, agentId, null, null,      MemoryType.SEMANTIC,   MemoryScope.GLOBAL,     0.5f, 20));
        result.addAll(queryActive(userId, agentId, null, null,      MemoryType.PROCEDURAL, MemoryScope.GLOBAL,     0f,   20));
        if (sessionId != null)
            result.addAll(queryActive(userId, agentId, sessionId, null, MemoryType.GOAL, MemoryScope.SESSION, 0f,   5));
        result.addAll(queryActive(userId, agentId, null, workspaceId, MemoryType.RELATION, MemoryScope.WORKSPACE,  0.6f, 10));
        return deduplicate(result);
    }

    private List<Memory> loadConditional(String userId, String agentId, String sessionId, String workspaceId, String userMessage) {
        List<Memory> result = new ArrayList<>();
        result.addAll(topN(queryActive(userId, agentId, null, null, MemoryType.EPISODIC, MemoryScope.GLOBAL, 0f, 50), 5));
        if (sessionId != null) {
            result.addAll(topN(queryActive(userId, agentId, sessionId, null, MemoryType.EPISODIC,   MemoryScope.SESSION, 0f, 50), 10));
            result.addAll(queryActive(userId, agentId, sessionId, null,      MemoryType.PROCEDURAL, MemoryScope.SESSION, 0f, 20));
            result.addAll(topN(queryActive(userId, agentId, sessionId, null, MemoryType.RELATION, MemoryScope.SESSION, 0f, 20), 5));
        }
        return deduplicate(result);
    }

    private String format(List<Memory> memories) {
        Map<MemoryType, List<Memory>> byType = memories.stream()
                .collect(Collectors.groupingBy(Memory::type));

        StringBuilder sb = new StringBuilder("## Agent Memory\n");
        int budget = TOKEN_BUDGET_CHARS - sb.length();
        budget = appendSection(sb, "当前目标",   byType.get(MemoryType.GOAL),       budget);
        budget = appendSection(sb, "全局事实",   byType.get(MemoryType.SEMANTIC),    budget);
        budget = appendRelationSection(sb,      byType.get(MemoryType.RELATION),   budget);
        budget = appendSection(sb, "约定/偏好",  byType.get(MemoryType.PROCEDURAL),  budget);
                 appendSection(sb, "最近事件",   byType.get(MemoryType.EPISODIC),    budget);
        return sb.toString();
    }

    private int appendSection(StringBuilder sb, String title, List<Memory> items, int budget) {
        if (items == null || items.isEmpty() || budget <= 0) return budget;
        if (title != null) sb.append("### ").append(title).append("\n");
        for (Memory m : items) {
            String line = formatLine(m) + "\n";
            if (budget - line.length() < 0) break;
            sb.append(line);
            budget -= line.length();
        }
        return budget;
    }

    /** Format RELATION memories as structured triples when available. */
    private int appendRelationSection(StringBuilder sb, List<Memory> items, int budget) {
        if (items == null || items.isEmpty() || budget <= 0) return budget;
        sb.append("### 关系结构\n");
        for (Memory m : items) {
            String line;
            if (m.subjectEntity() != null && m.predicate() != null && m.objectEntity() != null) {
                String confTag = m.confidence() < 0.7f ? " ⚠️待确认" : "";
                line = "- [REL] " + m.subjectEntity() + " --[" + m.predicate() + "]--> " + m.objectEntity() + confTag + "\n";
            } else {
                line = formatLine(m) + "\n";
            }
            if (budget - line.length() < 0) break;
            sb.append(line);
            budget -= line.length();
        }
        return budget;
    }

    private String formatLine(Memory m) {
        return switch (m.type()) {
            case GOAL       -> "- [GOAL] " + m.content();
            case SEMANTIC   -> "- [FACT] " + m.content();
            case RELATION   -> "- [REL] "  + m.content();
            case PROCEDURAL -> "- [CONVENTION] " + m.content();
            case EPISODIC   -> {
                String ts = m.createdAt() != null
                        ? m.createdAt().toString().substring(0, 10) : "?";
                yield "- [EVENT " + ts + "] " + m.content();
            }
        };
    }

    private List<Memory> queryActive(String userId, String agentId, String sessionId, String workspaceId,
                                     MemoryType type, MemoryScope scope,
                                     float minImportance, int limit) {
        return store.query(MemoryQuery.forAgent(agentId)
                .userId(userId)
                .session(sessionId)
                .workspace(workspaceId)
                .types(type)
                .scopes(scope)
                .minImportance(minImportance)
                .limit(limit)
                .build());
    }

    private List<Memory> topN(List<Memory> memories, int n) {
        return memories.stream()
                .sorted(Comparator.comparingDouble((Memory m) -> m.retrievalScore(0.5)).reversed())
                .limit(n)
                .toList();
    }

    private List<Memory> deduplicate(List<Memory> memories) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<Memory> result = new ArrayList<>();
        for (Memory m : memories) {
            if (m != null && seen.add(m.id())) result.add(m);
        }
        return result;
    }

    private boolean containsId(List<Memory> list, UUID id) {
        return list.stream().anyMatch(m -> m != null && m.id().equals(id));
    }
}
