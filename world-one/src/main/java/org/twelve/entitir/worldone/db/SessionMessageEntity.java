package org.twelve.entitir.worldone.db;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA 实体：Session 消息历史（用户 / AI / 系统）。
 * 对应数据库表 {@code session_messages}。
 *
 * <p>{@code agentSessionId} 对应 {@link org.twelve.entitir.worldone.GenericAgentLoop} 的 key，
 * 用于重建 LLM 上下文（全量历史）。
 * <p>{@code uiSessionId} 对应 {@link org.twelve.entitir.worldone.db.UiSessionEntity#id}，
 * 用于 UI 面板展示该 session 自身产生的消息。
 */
@Entity
@Table(name = "session_messages",
       indexes = {
           @Index(name = "idx_msg_agent_session", columnList = "agent_session_id, created_at"),
           @Index(name = "idx_msg_ui_session",    columnList = "ui_session_id, created_at")
       })
public class SessionMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_session_id", nullable = false, length = 100)
    private String agentSessionId;

    /** UiSession.id — 用于 UI 面板按任务 session 展示消息，可为 null（历史数据兼容） */
    @Column(name = "ui_session_id", length = 100)
    private String uiSessionId;

    /** user | assistant | system */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at")
    private Instant createdAt;

    public SessionMessageEntity() {}

    public SessionMessageEntity(String agentSessionId, String uiSessionId, String role, String content) {
        this.agentSessionId = agentSessionId;
        this.uiSessionId    = uiSessionId;
        this.role           = role;
        this.content        = content;
        this.createdAt      = Instant.now();
    }

    public Long    getId()             { return id; }
    public String  getAgentSessionId() { return agentSessionId; }
    public String  getUiSessionId()    { return uiSessionId; }
    public String  getRole()           { return role; }
    public String  getContent()        { return content; }
    public Instant getCreatedAt()      { return createdAt; }
    public void    setContent(String content) { this.content = content; }
}
