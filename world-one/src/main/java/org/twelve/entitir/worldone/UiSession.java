package org.twelve.entitir.worldone;

/**
 * 前端可见的 session 元数据（左侧任务面板条目）。
 *
 * <ul>
 *   <li>type: {@code conversation} | {@code task} | {@code event}</li>
 *   <li>status: {@code active} | {@code completed} | {@code voided} | {@code null}（对话无状态）</li>
 *   <li>agentSessionId: 对应 {@link GenericAgentLoop} 的 key（即对话历史所在的 loop）</li>
 *   <li>widgetType: 若非 null，表示该 session 最后处于 Canvas 模式（如 "entity-graph"）</li>
 *   <li>canvasSessionId: app-side 设计会话 ID（如 world-entitir 的 WorldOneSession.id），
 *       用于恢复 canvas 状态</li>
 * </ul>
 */
public record UiSession(
        String id,
        String type,
        String name,
        String status,
        String agentSessionId,
        String widgetType,
        String canvasSessionId,
        String createdAt
) {
    public boolean isConversation() { return "conversation".equals(type); }
    public boolean isApp()          { return "app".equals(type); }

    /** 已完成或已作废，不在默认列表中展示。 */
    public boolean isArchived() {
        return "completed".equals(status) || "voided".equals(status);
    }

    /** 是否处于 Canvas 模式。 */
    public boolean hasCanvas() { return widgetType != null && !widgetType.isBlank(); }
}
