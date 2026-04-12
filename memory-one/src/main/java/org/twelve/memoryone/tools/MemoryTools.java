package org.twelve.memoryone.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.twelve.memoryone.loader.DefaultMemoryLoader;
import org.twelve.memoryone.loader.MemoryLoadResult;
import org.twelve.memoryone.model.*;
import org.twelve.memoryone.query.MemoryQuery;
import org.twelve.memoryone.store.JdbcMemoryStore;

import java.time.Instant;
import java.util.*;

/**
 * Memory 管理工具实现。
 *
 * <p>被 {@link org.twelve.memoryone.api.MemoryToolsController} 暴露为
 * {@code POST /api/tools/memory_*} 端点。
 */
@Component
public class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DELETED_MARKER = "__deleted__";
    private static final String DEFAULT_AGENT_ID = "memory-one";
    private static final String DEFAULT_USER_ID  = "default";

    @Autowired private JdbcMemoryStore     store;
    @Autowired private DefaultMemoryLoader loader;
    @Autowired private org.twelve.memoryone.db.MemoryRepository repo;

    // ── memory_load ───────────────────────────────────────────────────────

    /**
     * 加载当前对话的记忆上下文。
     *
     * <h3>两种加载策略</h3>
     * <ol>
     *   <li><b>主 session（无 sessionId）</b>：直接返回 Global Snapshot 叙述段落。
     *       快照由 consolidation 后异步生成，1 次 SQL，覆盖 GLOBAL 全5类核心记忆。
     *       无快照时 fallback 到完整 DB 查询。</li>
     *   <li><b>子 session（有 sessionId）</b>：返回纯结构化条目：
     *       GLOBAL(3类) + WORKSPACE(3类) + SESSION(5类)。
     *       结构化条目可精确引用（供 SUPERSEDE / GOAL_PROGRESS 等操作），无需叙述段落。</li>
     * </ol>
     *
     * @return {"ok": true, "memory_context": "...", "memory_ids": [...]}
     */
    public Map<String, Object> load(Map<String, Object> args, Map<String, Object> context) {
        String userId      = ctxOrArg(context, args, "userId",      "user_id",       DEFAULT_USER_ID);
        String sessionId   = ctxOrArg(context, args, "sessionId",   "session_id",    null);
        String workspaceId = ctxOrArg(context, args, "workspaceId", "workspace_id",  null);
        String agentId     = ctxOrArg(context, args, "agentId",     "agent_id",      DEFAULT_AGENT_ID);
        String userMsg     = str(args, "user_message", "");

        boolean isSubSession = sessionId != null && !sessionId.isBlank();

        if (isSubSession) {
            // 子 session：纯结构化 GLOBAL(3类) + WORKSPACE(3类) + SESSION(5类)
            MemoryLoadResult result = loader.loadSessionContext(userId, agentId, sessionId, workspaceId, userMsg);
            return Map.of(
                "ok",             true,
                "memory_context", result.isEmpty() ? "" : result.injectionText(),
                "memory_ids",     result.loadedIds().stream().map(UUID::toString).toList()
            );
        }

        // 主 session：Global Snapshot 叙述段落
        Optional<String> snapshot = store.loadSnapshot(agentId, userId);
        if (snapshot.isPresent()) {
            return Map.of("ok", true, "memory_context", "## 用户记忆快照\n" + snapshot.get(), "memory_ids", List.of());
        }

        // Fallback：无快照时完整查询（兼容首次启动 / snapshot 尚未生成）
        MemoryLoadResult result = loader.loadWithIds(userId, agentId, sessionId, workspaceId, userMsg);
        return Map.of(
            "ok",             true,
            "memory_context", result.isEmpty() ? "" : result.injectionText(),
            "memory_ids",     result.loadedIds().stream().map(UUID::toString).toList()
        );
    }

    // ── memory_query ──────────────────────────────────────────────────────

    public Map<String, Object> query(Map<String, Object> args, Map<String, Object> context) {
        String agentId     = ctxOrArg(context, args, "agentId",     "agent_id",     DEFAULT_AGENT_ID);
        String userId      = ctxOrArg(context, args, "userId",      "user_id",      DEFAULT_USER_ID);
        String sessionId   = ctxOrArg(context, args, "sessionId",   "session_id",   null);
        String workspaceId = ctxOrArg(context, args, "workspaceId", "workspace_id", null);
        boolean forManager = boolVal(args, "for_manager", false);
        String keyword     = str(args, "keyword",  null);
        String typeStr     = str(args, "type",     null);
        String scopeStr    = str(args, "scope",    null);
        int    limit       = intVal(args, "limit", 50);
        float  minImp      = floatVal(args, "min_importance", 0f);

        List<Memory> memories;
        if (forManager) {
            // Memory manager panel should show all user-owned active memories
            // across all sessions/workspaces for explicit administration.
            memories = repo.findAllActiveForUser(agentId, userId, Instant.now())
                    .stream()
                    .map(e -> store.findById(UUID.fromString(e.getId())).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
        } else {
            MemoryQuery.Builder qb = MemoryQuery.forAgent(agentId)
                    .userId(userId)
                    .session(sessionId)
                    .workspace(workspaceId)
                    .limit(limit)
                    .minImportance(minImp);

            if (typeStr  != null) qb = qb.types(MemoryType.valueOf(typeStr.toUpperCase()));
            if (scopeStr != null) qb = qb.scopes(MemoryScope.valueOf(scopeStr.toUpperCase()));
            if (keyword  != null) qb = qb.textSearch(keyword);
            memories = store.query(qb.build());
        }
        return Map.of("ok", true, "count", memories.size(),
                      "memories", memories.stream().map(this::memoryToMap).toList());
    }

    // ── memory_create ─────────────────────────────────────────────────────

    public Map<String, Object> create(Map<String, Object> args, Map<String, Object> context) {
        String agentId     = ctxOrArg(context, args, "agentId",     "agent_id",   DEFAULT_AGENT_ID);
        String userId      = ctxOrArg(context, args, "userId",      "user_id",    DEFAULT_USER_ID);
        String content     = str(args, "content",  null);
        String typeStr     = str(args, "type",     "SEMANTIC");
        String scopeStr    = str(args, "scope",    "GLOBAL");
        float  importance  = floatVal(args, "importance", 0.7f);

        if (content == null || content.isBlank()) return error("content is required");

        @SuppressWarnings("unchecked")
        List<String> tags = args.get("tags") instanceof List<?> tl ? (List<String>) tl : List.of();

        // Normalize: handle both RELATIONAL (legacy) and RELATION
        if ("RELATIONAL".equalsIgnoreCase(typeStr)) typeStr = "RELATION";
        MemoryType  type  = MemoryType.valueOf(typeStr.toUpperCase());
        MemoryScope scope = MemoryScope.valueOf(scopeStr.toUpperCase());

        // RELATION triple fields
        String subjectEntity = str(args, "subject_entity", null);
        String predicate     = str(args, "predicate", null);
        String objectEntity  = str(args, "object_entity", null);

        // RELATION type: lower confidence default (hallucination risk)
        float conf = type == MemoryType.RELATION ? 0.65f : 1.0f;

        // Scope-specific IDs from context
        String sessionId   = scope == MemoryScope.SESSION   ? ctxOrArg(context, args, "sessionId",   "session_id",   null) : null;
        String workspaceId = scope == MemoryScope.WORKSPACE ? ctxOrArg(context, args, "workspaceId", "workspace_id", null) : null;

        Instant now = Instant.now();
        Memory m = new Memory(
            UUID.randomUUID(), type, scope, agentId, userId, workspaceId,
            sessionId,
            content, null, tags,
            importance, conf, MemorySource.USER_STATED, MemoryHorizon.MEDIUM_TERM,
            subjectEntity, predicate, objectEntity,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
        store.save(m);
        return Map.of("ok", true, "memory", memoryToMap(m));
    }

    // ── memory_update ─────────────────────────────────────────────────────

    public Map<String, Object> update(Map<String, Object> args, Map<String, Object> context) {
        String idStr = str(args, "id", null);
        if (idStr == null) return error("id is required");

        Optional<Memory> opt = store.findById(UUID.fromString(idStr));
        if (opt.isEmpty()) return error("Memory not found: " + idStr);

        Memory old = opt.get();
        String newContent    = str(args, "content", null);
        Float  newImportance = args.containsKey("importance")
                ? ((Number) args.get("importance")).floatValue() : null;
        @SuppressWarnings("unchecked")
        List<String> newTags = args.get("tags") instanceof List<?> tl ? (List<String>) tl : null;

        // Allow changing type (except for RELATION — structure is different)
        MemoryType newType = null;
        String typeStr = str(args, "type", null);
        if (typeStr != null) {
            try {
                MemoryType parsed = MemoryType.valueOf(typeStr.toUpperCase());
                // Disallow converting to/from RELATION type
                if (parsed != MemoryType.RELATION && old.type() != MemoryType.RELATION) {
                    newType = parsed;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // Allow changing horizon
        MemoryHorizon newHorizon = null;
        String horizonStr = str(args, "horizon", null);
        if (horizonStr != null) {
            try { newHorizon = MemoryHorizon.valueOf(horizonStr.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        Instant now = Instant.now();
        String newSubject   = str(args, "subject_entity", null);
        String newPredicate = str(args, "predicate",      null);
        String newObject    = str(args, "object_entity",  null);
        Memory updated = new Memory(
            UUID.randomUUID(),
            newType    != null ? newType    : old.type(),
            old.scope(),
            old.agentId(), old.userId(), old.workspaceId(), old.sessionId(),
            newContent   != null ? newContent   : old.content(),
            old.structured(),
            newTags      != null ? newTags       : old.tags(),
            newImportance != null ? newImportance : old.importance(),
            old.confidence(), MemorySource.USER_STATED,
            newHorizon   != null ? newHorizon   : old.horizon(),
            newSubject   != null ? newSubject   : old.subjectEntity(),
            newPredicate != null ? newPredicate : old.predicate(),
            newObject    != null ? newObject    : old.objectEntity(),
            now, now, now, 0, old.expiresAt(),
            null, List.of(), old.linkedTo(), old.provenance()
        );
        store.supersede(old.id(), updated);
        return Map.of("ok", true, "memory", memoryToMap(updated));
    }

    // ── memory_supersede ──────────────────────────────────────────────────

    public Map<String, Object> supersede(Map<String, Object> args, Map<String, Object> context) {
        String oldIdStr   = str(args, "old_id",      null);
        String newContent = str(args, "new_content", null);
        if (oldIdStr == null || newContent == null) return error("old_id and new_content are required");

        Optional<Memory> opt = store.findById(UUID.fromString(oldIdStr));
        if (opt.isEmpty()) return error("Memory not found: " + oldIdStr);

        Memory old = opt.get();
        Instant now = Instant.now();
        Memory newer = new Memory(
            UUID.randomUUID(), old.type(), old.scope(),
            old.agentId(), old.userId(), old.workspaceId(), old.sessionId(),
            newContent, old.structured(), old.tags(),
            old.importance(), old.confidence(), MemorySource.USER_STATED, old.horizon(),
            old.subjectEntity(), old.predicate(), old.objectEntity(),
            now, now, now, 0, old.expiresAt(),
            null, List.of(), old.linkedTo(), old.provenance()
        );
        store.supersede(old.id(), newer);
        return Map.of("ok", true, "new_memory", memoryToMap(newer));
    }

    // ── memory_delete_request ─────────────────────────────────────────────
    //    LLM 路径：找到目标记忆，返回 sys.confirm canvas 让用户确认后再删除

    public Map<String, Object> deleteRequest(Map<String, Object> args, Map<String, Object> context) {
        String idStr    = str(args, "id",      null);
        String keyword  = str(args, "keyword", null);
        String agentId  = ctxOrArg(context, args, "agentId", "agent_id", DEFAULT_AGENT_ID);
        String userId   = ctxOrArg(context, args, "userId",  "user_id",  DEFAULT_USER_ID);
        log.info("[deleteRequest] called: id={} keyword={} userId={}", idStr, keyword, userId);

        List<Memory> targets = new ArrayList<>();
        if (idStr != null) {
            store.findById(UUID.fromString(idStr)).ifPresent(targets::add);
        } else if (keyword != null && !keyword.isBlank()) {
            MemoryQuery q = MemoryQuery.forAgent(agentId).userId(userId).textSearch(keyword).limit(20).build();
            targets.addAll(store.query(q));
        }

        if (targets.isEmpty()) {
            log.info("[deleteRequest] no targets found for id={} keyword={}", idStr, keyword);
            return Map.of("ok", false, "error", "未找到匹配的记忆，请确认 id 或 keyword");
        }
        log.info("[deleteRequest] found {} targets, building sys.confirm canvas", targets.size());

        List<String> summaries = targets.stream()
                .limit(5)
                .map(m -> "• " + m.content())
                .toList();
        String more = targets.size() > 5 ? "\n…共 " + targets.size() + " 条" : "";
        String message = "确定要删除以下记忆吗？此操作不可撤销。\n\n"
                + String.join("\n", summaries) + more;

        List<String> ids = targets.stream()
                .map(m -> m.id().toString())
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> confirmData = new LinkedHashMap<>();
        confirmData.put("mode",    "yes_no");
        confirmData.put("title",   targets.size() > 1
                ? "确认删除 " + targets.size() + " 条记忆"
                : "确认删除记忆");
        confirmData.put("message", message);
        confirmData.put("danger",  true);
        confirmData.put("yes", Map.of(
                "tool", "memory_delete_confirmed",
                "args", Map.of("ids", ids)
        ));
        confirmData.put("no", Map.of(
                "message", "已取消删除操作"
        ));

        return Map.of(
                "ok",     true,
                "status", "awaiting_confirmation",
                "message", "确认框已显示，等待用户确认。删除操作尚未执行，请勿说已删除。",
                "canvas", Map.of(
                        "action",      "open",
                        "widget_type", "sys.confirm",
                        "data",        confirmData
                )
        );
    }

    // ── memory_delete_confirmed ───────────────────────────────────────────
    //    由 sys.confirm 的 yes 按钮通过 ToolProxy 直接调用，执行真正的删除

    public Map<String, Object> deleteConfirmed(Map<String, Object> args, Map<String, Object> context) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) args.getOrDefault("ids", List.of());
        log.info("[deleteConfirmed] called: ids={}", ids);
        if (ids.isEmpty()) {
            String idStr = str(args, "id", null);
            if (idStr != null) ids = List.of(idStr);
        }
        if (ids.isEmpty()) return error("ids is required");

        int deleted = 0;
        for (String idStr : ids) {
            try {
                Optional<Memory> opt = store.findById(UUID.fromString(idStr));
                if (opt.isPresent()) {
                    doDelete(opt.get());
                    deleted++;
                }
            } catch (IllegalArgumentException ignored) {}
        }
        return Map.of("ok", true, "deleted_count", deleted);
    }

    // ── memory_delete ─────────────────────────────────────────────────────
    //    Widget ToolProxy 路径（memory-manager 删除按钮直接调用，已通过前端 sys.confirm 确认）

    public Map<String, Object> delete(Map<String, Object> args, Map<String, Object> context) {
        String idStr = str(args, "id", null);
        log.warn("[delete] DIRECT DELETE called! id={} — should only happen via deleteConfirmed", idStr);
        if (idStr == null) return error("id is required");

        Optional<Memory> opt = store.findById(UUID.fromString(idStr));
        if (opt.isEmpty()) return error("Memory not found: " + idStr);

        doDelete(opt.get());
        return Map.of("ok", true, "deleted_id", idStr);
    }

    private void doDelete(Memory m) {
        UUID deletedSentinel = UUID.nameUUIDFromBytes(DELETED_MARKER.getBytes());
        Instant now = Instant.now();
        Memory placeholder = new Memory(
            deletedSentinel.equals(m.id()) ? UUID.randomUUID() : deletedSentinel,
            m.type(), m.scope(),
            m.agentId(), m.userId(), null, null,
            "[deleted]", null, List.of(),
            0f, 0f, MemorySource.SYSTEM, MemoryHorizon.SHORT_TERM,
            null, null, null,
            now, now, now, 0, Instant.now().plusSeconds(1),
            null, List.of(), List.of(), List.of()
        );
        store.supersede(m.id(), placeholder);
    }

    // ── memory_promote ────────────────────────────────────────────────────

    public Map<String, Object> promote(Map<String, Object> args, Map<String, Object> context) {
        String idStr    = str(args, "id",        null);
        String scopeStr = str(args, "new_scope", null);
        if (idStr == null || scopeStr == null) return error("id and new_scope are required");

        Memory promoted = store.promote(UUID.fromString(idStr), MemoryScope.valueOf(scopeStr.toUpperCase()));
        return Map.of("ok", true, "memory", memoryToMap(promoted));
    }

    // ── memory_set_instruction ────────────────────────────────────────────

    public Map<String, Object> setInstruction(Map<String, Object> args, Map<String, Object> context) {
        String agentId   = ctxOrArg(context, args, "agentId",   "agent_id",   DEFAULT_AGENT_ID);
        String userId    = ctxOrArg(context, args, "userId",    "user_id",    DEFAULT_USER_ID);
        String content   = str(args, "content",  null);
        String scopeStr  = str(args, "scope",    "GLOBAL");
        String sessionId = ctxOrArg(context, args, "sessionId", "session_id", null);

        if (content == null || content.isBlank()) return error("content is required");

        MemoryScope   scope   = MemoryScope.valueOf(scopeStr.toUpperCase());
        MemoryHorizon horizon = scope == MemoryScope.GLOBAL ? MemoryHorizon.LONG_TERM : MemoryHorizon.MEDIUM_TERM;

        Instant now = Instant.now();
        Memory m = new Memory(
            UUID.randomUUID(),
            MemoryType.PROCEDURAL, scope,
            agentId, userId, null,
            scope == MemoryScope.SESSION ? sessionId : null,
            content, null, List.of("memory_instruction"),
            0.95f, 1.0f, MemorySource.USER_STATED, horizon,
            null, null, null,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
        store.save(m);
        return Map.of("ok", true,
                      "message", "已记录您的记忆指令，将从下一轮对话开始生效",
                      "memory", memoryToMap(m));
    }

    // ── memory_workspace_join ─────────────────────────────────────────────

    /**
     * Register that a user has participated in a workspace. Idempotent — creates
     * a WORKSPACE RELATION memory only if one doesn't already exist for this
     * user+workspaceId pair.
     *
     * <p>The record is: subject=userId --[contributed_to]--> object=workspaceId.
     * This allows any agent to answer "who has edited workspace X?" via entity-
     * anchored retrieval on the workspaceId.
     */
    public Map<String, Object> registerWorkspaceParticipation(Map<String, Object> args,
                                                               Map<String, Object> context) {
        String agentId       = ctxOrArg(context, args, "agentId",      "agent_id",       DEFAULT_AGENT_ID);
        String userId        = ctxOrArg(context, args, "userId",       "user_id",        DEFAULT_USER_ID);
        String workspaceId   = ctxOrArg(context, args, "workspaceId",  "workspace_id",   null);
        String workspaceTitle = str(args, "workspace_title", workspaceId);

        if (workspaceId == null || workspaceId.isBlank())
            return error("workspace_id is required");

        // Idempotency: skip if already registered
        long existing = repo.countWorkspaceParticipation(agentId, userId, workspaceId);
        if (existing > 0) return Map.of("ok", true, "created", false,
                                        "message", "participation already recorded");

        Instant now = Instant.now();
        String content = userId + " 参与编辑了工作空间 " + workspaceTitle;
        Memory m = new Memory(
            UUID.randomUUID(), MemoryType.RELATION, MemoryScope.WORKSPACE,
            agentId, userId, workspaceId,
            null,          // sessionId — workspace participation is not session-bound
            content, null, List.of("workspace_participation"),
            0.6f, 1.0f, MemorySource.SYSTEM, MemoryHorizon.LONG_TERM,
            userId, "contributed_to", workspaceId,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
        store.save(m);
        return Map.of("ok", true, "created", true, "memory", memoryToMap(m));
    }

    // ── 转换 ──────────────────────────────────────────────────────────────

    public Map<String, Object> memoryToMap(Memory m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",            m.id().toString());
        map.put("type",          m.type().name());
        map.put("scope",         m.scope().name());
        map.put("horizon",       m.horizon() != null ? m.horizon().name() : null);
        map.put("content",       m.content());
        map.put("importance",    m.importance());
        map.put("confidence",    m.confidence());
        map.put("source",        m.source().name());
        map.put("tags",          m.tags());
        map.put("user_id",       m.userId());
        map.put("created_at",    m.createdAt() != null ? m.createdAt().toString() : null);
        map.put("updated_at",    m.updatedAt() != null ? m.updatedAt().toString() : null);
        map.put("last_accessed", m.lastAccessed() != null ? m.lastAccessed().toString() : null);
        map.put("access_count",  m.accessCount());
        map.put("session_id",    m.sessionId());
        map.put("active",        m.isActive());
        // Triple fields (populated for RELATION type)
        if (m.subjectEntity() != null) map.put("subject_entity", m.subjectEntity());
        if (m.predicate()     != null) map.put("predicate",      m.predicate());
        if (m.objectEntity()  != null) map.put("object_entity",  m.objectEntity());
        return map;
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private static Map<String, Object> error(String msg) { return Map.of("ok", false, "error", msg); }

    private static String ctx(Map<String, Object> ctx, String key, String defaultVal) {
        if (ctx == null) return defaultVal;
        Object v = ctx.get(key);
        return v instanceof String s ? s : defaultVal;
    }

    private static String ctxOrArg(Map<String, Object> ctx, Map<String, Object> args,
                                   String ctxKey, String argKey, String defaultVal) {
        String v = ctx(ctx, ctxKey, null);
        if (v != null) return v;
        return str(args, argKey, defaultVal);
    }

    private static String str(Map<String, Object> m, String key, String defaultVal) {
        Object v = m.get(key);
        return v instanceof String s ? s : defaultVal;
    }

    private static int intVal(Map<String, Object> m, String key, int defaultVal) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : defaultVal;
    }

    private static float floatVal(Map<String, Object> m, String key, float defaultVal) {
        Object v = m.get(key);
        return v instanceof Number n ? n.floatValue() : defaultVal;
    }

    private static boolean boolVal(Map<String, Object> m, String key, boolean defaultVal) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : defaultVal;
    }
}
