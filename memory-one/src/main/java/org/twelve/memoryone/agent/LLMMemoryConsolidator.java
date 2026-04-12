package org.twelve.memoryone.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.twelve.entitir.aip.LLMCaller;
import org.twelve.entitir.ontology.llm.LLMConfig;
import org.twelve.memoryone.config.MemoryOneConfigStore;
import org.twelve.memoryone.model.*;
import org.twelve.memoryone.query.MemoryQuery;
import org.twelve.memoryone.store.JdbcMemoryStore;
import org.twelve.memoryone.loader.DefaultMemoryLoader;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * LLM 驱动的 Memory 整合器。
 *
 * <h2>触发方式（新架构）</h2>
 * <p>由 worldone 通过调用 memory_consolidate skill 触发，
 * worldone 将本轮完整 turn 消息注入请求体（inject_context.turn_messages=true）。
 *
 * <h2>工作流程</h2>
 * <pre>
 * 1. 构建 Memory Agent 提示词
 * 2. 构建用户消息：本轮对话 + 当前 active memories + 当前 GOAL memories
 * 3. 用 callTextOnly() 调用 LLM
 * 4. 解析 LLM 返回的操作 JSON 数组
 * 5. 执行操作：CREATE / SUPERSEDE / PROMOTE / GOAL_PROGRESS / LINK / MARK_CONTRADICTION
 * </pre>
 */
@Component
public class LLMMemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(LLMMemoryConsolidator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired private JdbcMemoryStore          store;
    @Autowired private DefaultMemoryLoader      memoryLoader;
    @Autowired private MemoryAgentPromptBuilder promptBuilder;
    @Autowired private MemoryOneConfigStore     configStore;

    /**
     * 异步整合：接收 worldone 注入的 turn 消息，后台运行 LLM 分析。
     *
     * @param userId          用户 ID
     * @param agentId         Agent ID（如 "worldone"）
     * @param sessionId       会话 ID
     * @param turn            本轮完整对话消息（由 worldone 注入）
     * @param activeMemoryIds 本轮已加载的 memory IDs（可为空）
     */
    public void consolidate(String userId, String agentId, String sessionId, String workspaceId,
                            List<Map<String, Object>> turn,
                            List<UUID> activeMemoryIds) {
        CompletableFuture.runAsync(() -> {
            try {
                doConsolidate(userId, agentId, sessionId, workspaceId, turn,
                        activeMemoryIds != null ? activeMemoryIds : List.of());
            } catch (Exception e) {
                log.warn("[MemoryAgent] consolidation failed for session={}: {}", sessionId, e.getMessage());
            }
        }, executor);
    }

    /** Backward-compatible overload for callers without workspaceId. */
    public void consolidate(String userId, String agentId, String sessionId,
                            List<Map<String, Object>> turn,
                            List<UUID> activeMemoryIds) {
        consolidate(userId, agentId, sessionId, null, turn, activeMemoryIds);
    }

    private void doConsolidate(String userId, String agentId, String sessionId, String workspaceId,
                                List<Map<String, Object>> turn,
                                List<UUID> activeMemoryIds) throws Exception {
        String workspaceTitle = resolveWorkspaceTitle(workspaceId, turn);
        String systemPrompt = promptBuilder.build(agentId, userId, sessionId, workspaceId, workspaceTitle);
        String userMsg = buildUserMessage(userId, agentId, sessionId, turn, activeMemoryIds);

        List<Map<String, Object>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user",   "content", userMsg)
        );

        LLMCaller llm = new LLMCaller(buildLlmConfig());
        LLMCaller.LLMResponse resp = llm.callTextOnly(messages, 2048);

        String raw = resp.content();
        if (raw == null || raw.isBlank()) return;

        String json = extractJson(raw);
        if (json == null) return;

        List<Map<String, Object>> ops = JSON.readValue(json,
                new TypeReference<List<Map<String, Object>>>() {});

        for (Map<String, Object> op : ops) {
            try {
                executeOp(userId, agentId, sessionId, workspaceId, op);
            } catch (Exception e) {
                log.debug("[MemoryAgent] op failed: {} - {}", op.get("op"), e.getMessage());
            }
        }
        log.debug("[MemoryAgent] session={} processed {} ops", sessionId, ops.size());

        // 整合完成后，异步更新 global snapshot（不阻塞当前线程）
        generateAndCacheSnapshot(userId, agentId);
    }

    /**
     * 整合完成后生成并缓存 memory snapshot。
     *
     * <p>读取该用户所有 GLOBAL 高重要性记忆，用 LLM 生成一段自然语言叙述，
     * 存为 tag=[snapshot] 的 GLOBAL SEMANTIC 记忆（SUPERSEDE 旧快照）。
     * 下次 memory_load 时直接读此快照，无需遍历全量记忆条目。
     */
    private void generateAndCacheSnapshot(String userId, String agentId) {
        try {
            // ── 收集 GLOBAL 记忆作为快照源 ────────────────────────────────────
            // SEMANTIC：门槛 0.4（宁多勿缺，身份/事实类）
            // PROCEDURAL：全部（沟通约定、偏好——永久生效）
            // RELATION：门槛 0.5（确认度较高的关系）
            // GOAL：全部活跃目标（superseded 的已被过滤）
            // EPISODIC：门槛 0.6（重要人生事件，非日常流水）
            List<Memory> globals = new ArrayList<>();
            globals.addAll(loadType(userId, agentId, MemoryType.SEMANTIC,   MemoryScope.GLOBAL, 0.4f, 30));
            globals.addAll(loadType(userId, agentId, MemoryType.PROCEDURAL, MemoryScope.GLOBAL, 0f,   20));
            globals.addAll(loadType(userId, agentId, MemoryType.RELATION,   MemoryScope.GLOBAL, 0.5f, 15));
            globals.addAll(loadType(userId, agentId, MemoryType.GOAL,       MemoryScope.GLOBAL, 0f,   10));
            globals.addAll(loadType(userId, agentId, MemoryType.EPISODIC,   MemoryScope.GLOBAL, 0.6f, 10));

            if (globals.isEmpty()) {
                log.debug("[MemorySnapshot] No global memories for userId={}, skip snapshot", userId);
                return;
            }

            String snapshotText = callLlmForSnapshot(globals);
            if (snapshotText == null || snapshotText.isBlank()) return;

            // 把旧 snapshot 标记为 superseded，保存新快照
            Instant now = Instant.now();
            Memory newSnapshot = new Memory(
                UUID.randomUUID(), MemoryType.SEMANTIC, MemoryScope.GLOBAL,
                agentId, userId, null, null,
                snapshotText, null, List.of("snapshot"),
                1.0f, 1.0f, MemorySource.INFERRED, MemoryHorizon.LONG_TERM,
                null, null, null,
                now, now, now, 0, null,
                null, List.of(), List.of(), List.of()
            );

            // Supersede previous snapshot if exists, otherwise save fresh
            Optional<UUID> oldSnapshotId = store.findSnapshotId(agentId, userId);
            if (oldSnapshotId.isPresent()) {
                store.supersede(oldSnapshotId.get(), newSnapshot);
            } else {
                store.save(newSnapshot);
            }
            log.debug("[MemorySnapshot] Snapshot updated for userId={}, {} chars", userId, snapshotText.length());

        } catch (Exception e) {
            log.warn("[MemorySnapshot] Failed to generate snapshot for userId={}: {}", userId, e.getMessage());
        }
    }

    private List<Memory> loadType(String userId, String agentId, MemoryType type, MemoryScope scope,
                                  float minImportance, int limit) {
        return store.query(MemoryQuery.forAgent(agentId)
                .userId(userId)
                .types(type)
                .scopes(scope)
                .minImportance(minImportance)
                .limit(limit)
                .build());
    }

    private String callLlmForSnapshot(List<Memory> memories) throws Exception {
        // 按类型分组，构造简洁的输入文本
        StringBuilder input = new StringBuilder();
        Map<MemoryType, List<Memory>> byType = memories.stream()
                .collect(java.util.stream.Collectors.groupingBy(Memory::type));

        appendSnapshotSection(input, "事实/身份", byType.get(MemoryType.SEMANTIC));
        appendSnapshotSection(input, "约定/偏好", byType.get(MemoryType.PROCEDURAL));
        appendSnapshotSection(input, "关系",     byType.get(MemoryType.RELATION));
        appendSnapshotSection(input, "活跃目标", byType.get(MemoryType.GOAL));
        appendSnapshotSection(input, "重要经历", byType.get(MemoryType.EPISODIC));

        String systemPrompt = """
                你是一个记忆整理助手。根据以下用户的记忆条目，写一段简洁自然的第三人称叙述（100-300字），
                总结你对这个用户的了解，就像朋友的私人笔记。
                需要涵盖：身份/事实、人际关系、沟通约定与偏好、当前活跃目标、重要经历。
                只包含已知事实，不要添加推断、建议或评论。用流畅的中文叙述，不要用列表格式。
                """;

        List<Map<String, Object>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user",   "content", "## 记忆条目\n" + input)
        );

        LLMCaller llm = new LLMCaller(buildLlmConfig());
        LLMCaller.LLMResponse resp = llm.callTextOnly(messages, 512);
        String text = resp.content();
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    private void appendSnapshotSection(StringBuilder sb, String title, List<Memory> items) {
        if (items == null || items.isEmpty()) return;
        sb.append("【").append(title).append("】\n");
        for (Memory m : items) {
            if (m.type() == MemoryType.RELATION && m.subjectEntity() != null && m.predicate() != null) {
                sb.append("- ").append(m.subjectEntity()).append(" ").append(m.predicate())
                  .append(" ").append(m.objectEntity()).append("\n");
            } else {
                sb.append("- ").append(m.content()).append("\n");
            }
        }
    }

    private void executeOp(String userId, String agentId, String sessionId, String workspaceId,
                           Map<String, Object> op) {
        String opType = str(op, "op");
        if (opType == null) return;

        switch (opType) {
            case "CREATE" -> {
                Memory m = buildMemory(userId, agentId, sessionId, workspaceId, op);
                store.save(m);
                log.debug("[MemoryAgent] CREATE {} scope={} '{}'",
                        m.type(), m.scope(), truncate(m.content(), 60));
            }
            case "SUPERSEDE" -> {
                String oldIdStr  = str(op, "old_id");
                if (oldIdStr == null) return;
                UUID oldId = UUID.fromString(oldIdStr);
                @SuppressWarnings("unchecked")
                Map<String, Object> newData = (Map<String, Object>) op.get("new");
                if (newData == null) {
                    String newContent = str(op, "new_content");
                    if (newContent == null) return;
                    store.findById(oldId).ifPresent(old -> {
                        store.supersede(oldId, rebuildWithContent(old, newContent));
                        log.debug("[MemoryAgent] SUPERSEDE {} -> '{}'", oldIdStr, truncate(newContent, 60));
                    });
                } else {
                    store.findById(oldId).ifPresent(old ->
                        store.supersede(oldId, buildMemory(userId, agentId, sessionId, workspaceId, newData)));
                }
            }
            case "PROMOTE" -> {
                String idStr      = str(op, "id");
                String newScopeStr = str(op, "new_scope");
                if (idStr == null || newScopeStr == null) return;
                store.promote(UUID.fromString(idStr), MemoryScope.valueOf(newScopeStr));
                log.debug("[MemoryAgent] PROMOTE {} -> {}", idStr, newScopeStr);
            }
            case "GOAL_PROGRESS" -> {
                String idStr = str(op, "id");
                String note  = str(op, "progress_note");
                if (idStr == null || note == null) return;
                UUID id = UUID.fromString(idStr);
                store.findById(id).ifPresent(old -> {
                    store.supersede(id, rebuildWithContent(old, old.content() + " [进度] " + note));
                    log.debug("[MemoryAgent] GOAL_PROGRESS {}", idStr);
                });
            }
            case "LINK" -> {
                String fromId = str(op, "from_id");
                String toId   = str(op, "to_id");
                String lt     = str(op, "link_type");
                if (fromId == null || toId == null || lt == null) return;
                float weight = op.containsKey("weight")
                        ? ((Number) op.get("weight")).floatValue() : 0.8f;
                store.addLink(UUID.fromString(fromId),
                        new MemoryLink(UUID.fromString(toId), LinkType.valueOf(lt), weight));
            }
            case "MARK_CONTRADICTION" -> {
                String id1 = str(op, "id1");
                String id2 = str(op, "id2");
                if (id1 == null || id2 == null) return;
                store.markContradiction(UUID.fromString(id1), UUID.fromString(id2));
                log.debug("[MemoryAgent] MARK_CONTRADICTION {} ↔ {}", id1, id2);
            }
        }
    }

    private Memory buildMemory(String userId, String agentId, String sessionId, String workspaceId,
                               Map<String, Object> data) {
        String typeStr  = str(data, "type");
        String scopeStr = str(data, "scope");
        // Normalize: old prompt used "RELATIONAL", new prompt uses "RELATION"
        if ("RELATIONAL".equals(typeStr)) typeStr = "RELATION";
        MemoryType  type  = typeStr  != null ? MemoryType.valueOf(typeStr)   : MemoryType.SEMANTIC;
        MemoryScope scope = scopeStr != null ? MemoryScope.valueOf(scopeStr) : MemoryScope.SESSION;

        String srcStr = str(data, "source");
        MemorySource source = srcStr != null ? MemorySource.valueOf(srcStr) : MemorySource.INFERRED;

        String horizonStr = str(data, "horizon");
        MemoryHorizon horizon = horizonStr != null
                ? MemoryHorizon.valueOf(horizonStr) : inferHorizon(type, scope);

        float importance = data.containsKey("importance")
                ? ((Number) data.get("importance")).floatValue() : 0.5f;

        // RELATION type default confidence is lower (0.65) — extraction may hallucinate
        float defaultConf = type == MemoryType.RELATION ? 0.65f : source.defaultConfidence();
        float confidence = data.containsKey("confidence")
                ? ((Number) data.get("confidence")).floatValue() : defaultConf;

        @SuppressWarnings("unchecked")
        List<String> tags = data.containsKey("tags") ? (List<String>) data.get("tags") : List.of();

        String content = str(data, "content");
        if (content == null) content = "";

        // RELATION triple fields
        String subjectEntity = str(data, "subject_entity");
        String predicate     = str(data, "predicate");
        String objectEntity  = str(data, "object_entity");
        // Auto-build content description for RELATION if not provided
        if (type == MemoryType.RELATION && (content.isBlank()) && subjectEntity != null && predicate != null && objectEntity != null) {
            content = subjectEntity + " " + predicate + " " + objectEntity;
        }

        // Scope-specific IDs
        String effectiveSessionId   = scope == MemoryScope.SESSION   ? sessionId   : null;
        String effectiveWorkspaceId = scope == MemoryScope.WORKSPACE ? workspaceId : null;

        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), type, scope,
            agentId, userId, effectiveWorkspaceId,
            effectiveSessionId,
            content, str(data, "structured"), tags,
            importance, confidence, source, horizon,
            subjectEntity, predicate, objectEntity,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
    }

    private static MemoryHorizon inferHorizon(MemoryType type, MemoryScope scope) {
        return switch (type) {
            case PROCEDURAL -> scope == MemoryScope.GLOBAL ? MemoryHorizon.LONG_TERM  : MemoryHorizon.MEDIUM_TERM;
            case SEMANTIC   -> scope == MemoryScope.GLOBAL ? MemoryHorizon.LONG_TERM  : MemoryHorizon.SHORT_TERM;
            case GOAL       -> MemoryHorizon.MEDIUM_TERM;
            case RELATION   -> MemoryHorizon.MEDIUM_TERM;
            case EPISODIC   -> scope == MemoryScope.SESSION ? MemoryHorizon.SHORT_TERM : MemoryHorizon.MEDIUM_TERM;
        };
    }

    private Memory rebuildWithContent(Memory old, String newContent) {
        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), old.type(), old.scope(),
            old.agentId(), old.userId(), old.workspaceId(), old.sessionId(),
            newContent, old.structured(), old.tags(),
            old.importance(), old.confidence(), old.source(), old.horizon(),
            old.subjectEntity(), old.predicate(), old.objectEntity(),
            now, now, now, 0, old.expiresAt(),
            null, List.of(), old.linkedTo(), old.provenance()
        );
    }

    private String buildUserMessage(String userId, String agentId, String sessionId,
                                    List<Map<String, Object>> turn,
                                    List<UUID> activeMemoryIds) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 本轮对话\n");
        for (Map<String, Object> msg : turn) {
            String role    = str(msg, "role");
            Object content = msg.get("content");
            if (content == null || role == null) continue;
            String name = str(msg, "name");
            String label = "tool".equals(role) && name != null
                    ? "[tool:" + name + "]" : "[" + role + "]";
            sb.append(label).append(" ").append(content).append("\n");
        }

        sb.append("\n## 当前 Active Memories（本轮已注入 context 的记忆，先查这里再决定操作）\n");
        if (activeMemoryIds.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (UUID id : activeMemoryIds) {
                store.findById(id).ifPresent(m ->
                    sb.append("- [").append(id).append("]")
                      .append("[").append(m.type()).append("/").append(m.scope()).append("] ")
                      .append(m.content()).append("\n")
                );
            }
        }

        sb.append("\n## 当前 GOAL Memories（当前 session 所有活跃目标）\n");
        List<Memory> goals = memoryLoader.loadGoals(userId, agentId, sessionId);
        if (goals.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (Memory g : goals) {
                sb.append("- [").append(g.id()).append("] ").append(g.content()).append("\n");
            }
        }

        return sb.toString();
    }

    private LLMConfig buildLlmConfig() {
        return LLMConfig.builder()
                .apiKey(configStore.apiKey())
                .baseUrl(configStore.baseUrl())
                .model(configStore.model())
                .timeoutSeconds(configStore.timeout())
                .build();
    }

    private String extractJson(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("[")) return trimmed;
        int start = trimmed.indexOf("[");
        int end   = trimmed.lastIndexOf("]");
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return null;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : null;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * Try to derive a human-readable workspace title from the turn messages
     * (e.g., look for session name injected by world-one). Falls back to workspaceId.
     */
    private String resolveWorkspaceTitle(String workspaceId, List<Map<String, Object>> turn) {
        if (workspaceId == null) return null;
        // Check if any system message contains a workspace title hint
        for (Map<String, Object> msg : turn) {
            Object content = msg.get("content");
            if ("system".equals(msg.get("role")) && content instanceof String s) {
                if (s.contains("workspace_title:")) {
                    int idx = s.indexOf("workspace_title:") + "workspace_title:".length();
                    int end = s.indexOf('\n', idx);
                    if (end < 0) end = Math.min(idx + 60, s.length());
                    return s.substring(idx, end).trim();
                }
            }
        }
        return workspaceId; // fallback to ID
    }
}
