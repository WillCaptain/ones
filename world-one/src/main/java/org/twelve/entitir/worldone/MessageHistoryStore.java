package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.twelve.entitir.worldone.db.SessionMessageEntity;
import org.twelve.entitir.worldone.db.SessionMessageRepository;

import java.util.List;
import java.util.Map;

/**
 * 对话消息历史持久化，存入 PostgreSQL {@code session_messages} 表。
 *
 * <h2>用途</h2>
 * <ul>
 *   <li>每次对话后将 user / assistant 消息落库</li>
 *   <li>worldone 重启后从库里恢复 {@link GenericAgentLoop} 的对话上下文</li>
 * </ul>
 *
 * <p>目前只存储 user 和 assistant 文本消息；
 * tool_calls / tool_results 属于 LLM 内部轮次，不纳入持久化（当前迭代）。
 */
@Component
public class MessageHistoryStore {

    @Autowired
    private SessionMessageRepository repo;

    /** 保存一条消息到持久化存储（同时记录 agentSessionId 和 uiSessionId）。 */
    @Transactional
    public void save(String agentSessionId, String uiSessionId, String role, String content) {
        if (content == null || content.isBlank()) return;
        repo.save(new SessionMessageEntity(agentSessionId, uiSessionId, role, content));
    }

    /**
     * 加载某 agent session 的历史消息，返回 OpenAI 格式的 Map 列表。
     * 供 {@link GenericAgentLoop#restoreHistory} 使用（全量，用于 LLM 上下文重建）。
     */
    public List<Map<String, Object>> loadHistory(String agentSessionId) {
        return repo.findByAgentSessionIdOrderByCreatedAtAsc(agentSessionId)
                .stream()
                .<Map<String, Object>>map(e -> Map.of("role", e.getRole(), "content", e.getContent()))
                .toList();
    }

    /**
     * 加载某 ui session 的消息（仅该 session 自身产生的消息）。
     * 供 {@code GET /api/sessions/{id}/messages} 使用（UI 面板展示）。
     */
    public List<Map<String, Object>> loadHistoryForUi(String uiSessionId) {
        return repo.findByUiSessionIdOrderByCreatedAtAsc(uiSessionId)
                .stream()
                .<Map<String, Object>>map(e -> Map.of("role", e.getRole(), "content", e.getContent()))
                .toList();
    }
}
