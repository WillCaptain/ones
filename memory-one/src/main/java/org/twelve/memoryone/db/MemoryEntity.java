package org.twelve.memoryone.db;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA 实体：Agent Memory 记录。对应数据库表 {@code memories}。
 */
@Entity
@Table(name = "memories", indexes = {
    @Index(name = "idx_mem_agent_scope",  columnList = "agent_id, scope, type"),
    @Index(name = "idx_mem_user",         columnList = "user_id"),
    @Index(name = "idx_mem_session",      columnList = "session_id"),
    @Index(name = "idx_mem_active",       columnList = "superseded_by, expires_at"),
    @Index(name = "idx_mem_importance",   columnList = "agent_id, importance")
})
public class MemoryEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, length = 20)
    private String scope;

    @Column(name = "agent_id", nullable = false, length = 100)
    private String agentId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId = "default";

    @Column(name = "workspace_id", length = 100)
    private String workspaceId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String structured;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(nullable = false)
    private float importance = 0.5f;

    @Column(nullable = false)
    private float confidence = 0.8f;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(nullable = false, length = 20)
    private String horizon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_accessed", nullable = false)
    private Instant lastAccessed;

    @Column(name = "access_count", nullable = false)
    private int accessCount = 0;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "superseded_by", length = 36)
    private String supersededBy;

    /** RELATION type: triple subject entity name (e.g. "Alice") */
    @Column(name = "subject_entity", length = 200)
    private String subjectEntity;

    /** RELATION type: triple predicate (e.g. "is_manager_of") */
    @Column(name = "predicate", length = 200)
    private String predicate;

    /** RELATION type: triple object entity name (e.g. "Bob") */
    @Column(name = "object_entity", length = 200)
    private String objectEntity;

    @Column(columnDefinition = "TEXT")
    private String contradicts = "[]";

    @Column(name = "linked_to", columnDefinition = "TEXT")
    private String linkedTo = "[]";

    @Column(columnDefinition = "TEXT")
    private String provenance = "[]";

    public MemoryEntity() {}

    public String  getId()                          { return id; }
    public void    setId(String id)                 { this.id = id; }
    public String  getType()                        { return type; }
    public void    setType(String type)             { this.type = type; }
    public String  getScope()                       { return scope; }
    public void    setScope(String scope)           { this.scope = scope; }
    public String  getAgentId()                     { return agentId; }
    public void    setAgentId(String agentId)       { this.agentId = agentId; }
    public String  getUserId()                      { return userId; }
    public void    setUserId(String userId)         { this.userId = userId; }
    public String  getWorkspaceId()                 { return workspaceId; }
    public void    setWorkspaceId(String v)         { this.workspaceId = v; }
    public String  getSessionId()                   { return sessionId; }
    public void    setSessionId(String sessionId)   { this.sessionId = sessionId; }
    public String  getContent()                     { return content; }
    public void    setContent(String content)       { this.content = content; }
    public String  getStructured()                  { return structured; }
    public void    setStructured(String v)          { this.structured = v; }
    public String  getTags()                        { return tags; }
    public void    setTags(String tags)             { this.tags = tags; }
    public float   getImportance()                  { return importance; }
    public void    setImportance(float v)           { this.importance = v; }
    public float   getConfidence()                  { return confidence; }
    public void    setConfidence(float v)           { this.confidence = v; }
    public String  getSource()                      { return source; }
    public void    setSource(String source)         { this.source = source; }
    public String  getHorizon()                     { return horizon; }
    public void    setHorizon(String horizon)       { this.horizon = horizon; }
    public Instant getCreatedAt()                   { return createdAt; }
    public void    setCreatedAt(Instant v)          { this.createdAt = v; }
    public Instant getUpdatedAt()                   { return updatedAt; }
    public void    setUpdatedAt(Instant v)          { this.updatedAt = v; }
    public Instant getLastAccessed()                { return lastAccessed; }
    public void    setLastAccessed(Instant v)       { this.lastAccessed = v; }
    public int     getAccessCount()                 { return accessCount; }
    public void    setAccessCount(int v)            { this.accessCount = v; }
    public Instant getExpiresAt()                   { return expiresAt; }
    public void    setExpiresAt(Instant v)          { this.expiresAt = v; }
    public String  getSupersededBy()                { return supersededBy; }
    public void    setSupersededBy(String v)        { this.supersededBy = v; }
    public String  getSubjectEntity()               { return subjectEntity; }
    public void    setSubjectEntity(String v)       { this.subjectEntity = v; }
    public String  getPredicate()                   { return predicate; }
    public void    setPredicate(String v)           { this.predicate = v; }
    public String  getObjectEntity()                { return objectEntity; }
    public void    setObjectEntity(String v)        { this.objectEntity = v; }
    public String  getContradicts()                 { return contradicts; }
    public void    setContradicts(String v)         { this.contradicts = v; }
    public String  getLinkedTo()                    { return linkedTo; }
    public void    setLinkedTo(String v)            { this.linkedTo = v; }
    public String  getProvenance()                  { return provenance; }
    public void    setProvenance(String v)          { this.provenance = v; }
}
