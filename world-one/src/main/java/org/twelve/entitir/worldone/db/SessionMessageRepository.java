package org.twelve.entitir.worldone.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    /** 按时间顺序返回某 agent session 的所有消息（仅供 deleteLastTurn / deleteRange 内部使用）。
     *  <p>⚠️ 不要用于 LLM 上下文重建：跨 ui session 的消息会一起出来，导致主 session
     *  被其它 ui session 的历史污染。请使用 {@link #findByAgentSessionIdAndUiSessionIdOrderByCreatedAtAsc}。 */
    List<SessionMessageEntity> findByAgentSessionIdOrderByCreatedAtAsc(String agentSessionId);

    /** LLM 上下文重建专用：按 (agent_session_id, ui_session_id) 双键过滤，确保单一 ui session 隔离。 */
    List<SessionMessageEntity> findByAgentSessionIdAndUiSessionIdOrderByCreatedAtAsc(
            String agentSessionId, String uiSessionId);

    /** 按时间顺序返回某 ui session 的消息（UI 面板展示用）。 */
    List<SessionMessageEntity> findByUiSessionIdOrderByCreatedAtAsc(String uiSessionId);

    /** 删除某 agent session 的全部消息（清空对话历史）。 */
    void deleteByAgentSessionId(String agentSessionId);

    /** 按时间倒序返回某 ui session 的最后 N 条消息（用于重问时删除旧记录）。 */
    List<SessionMessageEntity> findTopNByUiSessionIdOrderByCreatedAtDesc(String uiSessionId);
}
