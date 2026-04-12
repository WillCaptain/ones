package org.twelve.entitir.worldone.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    /** 按时间顺序返回某 agent session 的所有消息（重建 LLM 上下文用）。 */
    List<SessionMessageEntity> findByAgentSessionIdOrderByCreatedAtAsc(String agentSessionId);

    /** 按时间顺序返回某 ui session 的消息（UI 面板展示用）。 */
    List<SessionMessageEntity> findByUiSessionIdOrderByCreatedAtAsc(String uiSessionId);

    /** 删除某 agent session 的全部消息（清空对话历史）。 */
    void deleteByAgentSessionId(String agentSessionId);

    /** 按时间倒序返回某 ui session 的最后 N 条消息（用于重问时删除旧记录）。 */
    List<SessionMessageEntity> findTopNByUiSessionIdOrderByCreatedAtDesc(String uiSessionId);
}
