package org.twelve.memoryone.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MemoryRepository extends JpaRepository<MemoryEntity, String> {

    /**
     * Load active memories for a session. Scope visibility rules:
     * <ul>
     *   <li>GLOBAL — visible only to the owning user (personal global facts)</li>
     *   <li>WORKSPACE — visible to ANY user sharing the same workspaceId (collaboration)</li>
     *   <li>SESSION — visible only to the owning user within the matching session</li>
     * </ul>
     */
    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE m.agentId = :agentId
          AND m.supersededBy IS NULL
          AND (m.expiresAt IS NULL OR m.expiresAt > :now)
          AND (:scope IS NULL OR m.scope = :scope)
          AND (:type IS NULL OR m.type = :type)
          AND m.importance >= :minImportance
          AND (
            (m.scope = 'GLOBAL'     AND m.userId = :userId)
            OR (m.scope = 'WORKSPACE' AND :workspaceId IS NOT NULL AND m.workspaceId = :workspaceId)
            OR (m.scope = 'SESSION'   AND m.userId = :userId
                                      AND (:sessionId IS NULL OR m.sessionId = :sessionId))
          )
        ORDER BY m.importance DESC, m.lastAccessed DESC
        """)
    List<MemoryEntity> findActive(
            @Param("agentId")       String agentId,
            @Param("userId")        String userId,
            @Param("scope")         String scope,
            @Param("type")          String type,
            @Param("sessionId")     String sessionId,
            @Param("workspaceId")   String workspaceId,
            @Param("minImportance") float minImportance,
            @Param("now")           Instant now);

    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE m.agentId = :agentId
          AND m.userId = :userId
          AND m.supersededBy IS NULL
          AND (m.expiresAt IS NULL OR m.expiresAt > :now)
          AND m.tags LIKE '%memory_instruction%'
          AND (m.scope = 'GLOBAL' OR m.sessionId = :sessionId)
        ORDER BY m.scope ASC, m.importance DESC
        """)
    List<MemoryEntity> findMemoryInstructions(
            @Param("agentId")   String agentId,
            @Param("userId")    String userId,
            @Param("sessionId") String sessionId,
            @Param("now")       Instant now);

    @Modifying
    @Query("""
        UPDATE MemoryEntity m
        SET m.lastAccessed = :now,
            m.accessCount  = m.accessCount + 1
        WHERE m.id IN :ids
        """)
    void recordAccess(@Param("ids") List<String> ids, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE MemoryEntity m SET m.supersededBy = :newId, m.updatedAt = :now WHERE m.id = :oldId")
    void markSuperseded(@Param("oldId") String oldId,
                        @Param("newId") String newId,
                        @Param("now")   Instant now);

    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE m.agentId = :agentId
          AND m.userId = :userId
          AND m.supersededBy IS NULL
          AND (m.expiresAt IS NULL OR m.expiresAt > :now)
          AND LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY m.importance DESC
        """)
    List<MemoryEntity> searchByKeyword(
            @Param("agentId")  String agentId,
            @Param("userId")   String userId,
            @Param("keyword")  String keyword,
            @Param("now")      Instant now);

    /**
     * Entity-anchored relation lookup. Scope visibility mirrors findActive:
     * GLOBAL = user-owned; WORKSPACE = cross-user by workspaceId; SESSION = user+session.
     */
    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE m.agentId = :agentId
          AND m.type = 'RELATION'
          AND m.supersededBy IS NULL
          AND (m.expiresAt IS NULL OR m.expiresAt > :now)
          AND (LOWER(m.subjectEntity) = LOWER(:entity) OR LOWER(m.objectEntity) = LOWER(:entity))
          AND (
            (m.scope = 'GLOBAL'     AND m.userId = :userId)
            OR (m.scope = 'WORKSPACE' AND :workspaceId IS NOT NULL AND m.workspaceId = :workspaceId)
            OR (m.scope = 'SESSION'   AND m.userId = :userId
                                      AND (:sessionId IS NULL OR m.sessionId = :sessionId))
          )
        ORDER BY m.confidence DESC, m.importance DESC
        """)
    List<MemoryEntity> findRelationsByEntity(
            @Param("agentId")     String agentId,
            @Param("userId")      String userId,
            @Param("entity")      String entity,
            @Param("sessionId")   String sessionId,
            @Param("workspaceId") String workspaceId,
            @Param("now")         Instant now);

    /** Check if a participation record already exists for this user+workspace (idempotency). */
    @Query("""
        SELECT COUNT(m) FROM MemoryEntity m
        WHERE m.agentId = :agentId
          AND m.userId = :userId
          AND m.workspaceId = :workspaceId
          AND m.type = 'RELATION'
          AND m.scope = 'WORKSPACE'
          AND m.predicate = 'contributed_to'
          AND m.supersededBy IS NULL
        """)
    long countWorkspaceParticipation(
            @Param("agentId")     String agentId,
            @Param("userId")      String userId,
            @Param("workspaceId") String workspaceId);

    /**
     * Load the latest memory snapshot record (tag="snapshot", GLOBAL scope).
     * Returns at most 1 result, ordered by updatedAt DESC.
     */
    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE m.agentId = :agentId
          AND m.userId = :userId
          AND m.supersededBy IS NULL
          AND m.tags LIKE '%snapshot%'
        ORDER BY m.updatedAt DESC
        """)
    List<MemoryEntity> findSnapshot(
            @Param("agentId") String agentId,
            @Param("userId")  String userId);

    /**
     * Management panel query: load all active memories owned by the user across
     * all sessions/workspaces (for explicit memory administration).
     */
    @Query("""
        SELECT m FROM MemoryEntity m
        WHERE m.agentId = :agentId
          AND m.userId = :userId
          AND m.supersededBy IS NULL
          AND (m.expiresAt IS NULL OR m.expiresAt > :now)
        ORDER BY m.updatedAt DESC, m.importance DESC
        """)
    List<MemoryEntity> findAllActiveForUser(
            @Param("agentId") String agentId,
            @Param("userId")  String userId,
            @Param("now")     Instant now);
}
