package org.twelve.memoryone.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 核心 Memory 记录（不可变 record）。
 *
 * <p>更新通过 SUPERSEDE 产生新记录，旧记录保留为历史。
 */
public record Memory(
    UUID         id,
    MemoryType   type,
    MemoryScope  scope,

    String agentId,
    String userId,
    String workspaceId,
    String sessionId,

    String       content,
    String       structured,
    List<String> tags,

    float        importance,
    float        confidence,
    MemorySource source,
    MemoryHorizon horizon,

    /** RELATION type: triple subject (nullable for non-relation types) */
    String subjectEntity,
    /** RELATION type: triple predicate (nullable for non-relation types) */
    String predicate,
    /** RELATION type: triple object (nullable for non-relation types) */
    String objectEntity,

    Instant createdAt,
    Instant updatedAt,
    Instant lastAccessed,
    int     accessCount,
    Instant expiresAt,

    UUID         supersededBy,
    List<UUID>   contradicts,
    List<MemoryLink> linkedTo,
    List<UUID>   provenance
) {
    public static final String DEFAULT_USER_ID = "default";

    public static Memory globalFact(String agentId, String userId, String content, float importance) {
        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), MemoryType.SEMANTIC, MemoryScope.GLOBAL,
            agentId, userId, null, null,
            content, null, List.of(),
            importance, MemorySource.INFERRED.defaultConfidence(),
            MemorySource.INFERRED, MemoryHorizon.LONG_TERM,
            null, null, null,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
    }

    public static Memory sessionGoal(String agentId, String userId, String sessionId, String content) {
        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), MemoryType.GOAL, MemoryScope.SESSION,
            agentId, userId, null, sessionId,
            content, null, List.of(),
            0.9f, 1.0f,
            MemorySource.SYSTEM, MemoryHorizon.MEDIUM_TERM,
            null, null, null,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
    }

    public static Memory sessionEvent(String agentId, String userId, String sessionId,
                                      String content, MemorySource source) {
        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), MemoryType.EPISODIC, MemoryScope.SESSION,
            agentId, userId, null, sessionId,
            content, null, List.of(),
            0.5f, source.defaultConfidence(),
            source, MemoryHorizon.SHORT_TERM,
            null, null, null,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
    }

    /** Create a RELATION triple memory (confidence=0.65 — lower due to extraction risk). */
    public static Memory relation(String agentId, String userId, String sessionId,
                                  String subject, String pred, String object,
                                  MemoryScope scope) {
        String desc = subject + " " + pred + " " + object;
        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), MemoryType.RELATION, scope,
            agentId, userId, null, scope == MemoryScope.SESSION ? sessionId : null,
            desc, null, List.of(),
            0.7f, 0.65f,               // lower confidence — LLM extraction may hallucinate
            MemorySource.INFERRED, MemoryHorizon.LONG_TERM,
            subject, pred, object,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of()
        );
    }

    public boolean isActive() {
        return supersededBy == null
            && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    public Memory withScope(MemoryScope newScope) {
        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), type, newScope,
            agentId, userId,
            workspaceId, newScope == MemoryScope.SESSION ? sessionId : null,
            content, structured, tags,
            importance, confidence, source, horizon,
            subjectEntity, predicate, objectEntity,
            now, now, now, 0, expiresAt,
            null, List.of(), linkedTo, provenance
        );
    }

    public Memory withContent(String newContent) {
        Instant now = Instant.now();
        return new Memory(
            UUID.randomUUID(), type, scope,
            agentId, userId,
            workspaceId, sessionId,
            newContent, structured, tags,
            importance, confidence, source, horizon,
            subjectEntity, predicate, objectEntity,
            now, now, now, 0, expiresAt,
            null, List.of(), linkedTo, provenance
        );
    }

    public double retrievalScore(double semanticSimilarity) {
        double hoursSinceAccess = (Instant.now().toEpochMilli()
                - lastAccessed.toEpochMilli()) / 3_600_000.0;
        double recency = Math.exp(-0.1 * hoursSinceAccess);
        return 0.4 * recency + 0.3 * importance + 0.3 * semanticSimilarity;
    }
}
