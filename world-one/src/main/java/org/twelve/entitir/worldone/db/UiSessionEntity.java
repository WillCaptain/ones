package org.twelve.entitir.worldone.db;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA 实体：World One 前端 session 元数据。
 * 对应数据库表 {@code ui_sessions}。
 */
@Entity
@Table(name = "ui_sessions")
public class UiSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 20)
    private String type;           // conversation | task | event

    @Column(nullable = false)
    private String name;

    @Column(length = 20)
    private String status;         // active | completed | voided | null

    @Column(name = "agent_session_id", length = 36)
    private String agentSessionId;

    /** Canvas 模式：当前激活的 widget 类型（如 "entity-graph"），null 表示 Chat Mode。 */
    @Column(name = "widget_type", length = 50)
    private String widgetType;

    /** Canvas 模式：对应的 app-side design session ID（如 world-entitir 的 WorldOneSession.id）。 */
    @Column(name = "canvas_session_id", length = 36)
    private String canvasSessionId;

    @Column(name = "created_at")
    private Instant createdAt;

    public UiSessionEntity() {}

    public UiSessionEntity(String id, String type, String name,
                           String status, String agentSessionId, Instant createdAt) {
        this.id             = id;
        this.type           = type;
        this.name           = name;
        this.status         = status;
        this.agentSessionId = agentSessionId;
        this.createdAt      = createdAt;
    }

    // ── getters / setters ──────────────────────────────────────────────────
    public String getId()             { return id; }
    public void   setId(String id)    { this.id = id; }

    public String getType()           { return type; }
    public void   setType(String t)   { this.type = t; }

    public String getName()           { return name; }
    public void   setName(String n)   { this.name = n; }

    public String getStatus()         { return status; }
    public void   setStatus(String s) { this.status = s; }

    public String getAgentSessionId()              { return agentSessionId; }
    public void   setAgentSessionId(String aid)    { this.agentSessionId = aid; }

    public String getWidgetType()                  { return widgetType; }
    public void   setWidgetType(String wt)         { this.widgetType = wt; }

    public String getCanvasSessionId()             { return canvasSessionId; }
    public void   setCanvasSessionId(String csid)  { this.canvasSessionId = csid; }

    public Instant getCreatedAt()               { return createdAt; }
    public void    setCreatedAt(Instant instant) { this.createdAt = instant; }
}
