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
 * <p>存储 user、assistant 文本消息，以及 widget 卡片消息（role=widget，UI 专用）。
 * widget 消息在 {@link #loadHistory} 中会被转为 assistant 占位，避免非法 role 传入 LLM API；
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
                .<Map<String, Object>>map(e -> {
                    if ("widget".equals(e.getRole())) {
                        return Map.of("role", "assistant", "content", "OK");
                    }
                    return Map.of("role", e.getRole(), "content", e.getContent());
                })
                .toList();
    }

    /**
     * 将某 ui session 最后一条 widget 消息标记为已处理（追加 "processed":true 到 JSON）。
     * 前端切换到 task session 时调用，刷新后"已处理"卡片仍能正确显示。
     */
    @Transactional
    public void markLastWidgetProcessed(String uiSessionId) {
        List<SessionMessageEntity> msgs = repo.findByUiSessionIdOrderByCreatedAtAsc(uiSessionId);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            SessionMessageEntity e = msgs.get(i);
            if ("widget".equals(e.getRole())) {
                String json = e.getContent();
                // 在 JSON 末尾注入 "processed":true（兼容现有 JSON 格式）
                if (json != null && json.trim().endsWith("}")) {
                    String updated = json.trim().substring(0, json.trim().length() - 1)
                            + ",\"processed\":true}";
                    e.setContent(updated);
                    repo.save(e);
                }
                return;
            }
        }
    }

    /** 加载某 ui session 的消息（仅该 session 自身产生的消息）。
     * 供 {@code GET /api/sessions/{id}/messages} 使用（UI 面板展示）。
     * <p>widget 消息以 {@code {"role":"widget","widgetJson":"..."}} 格式返回，
     * 前端识别后重建 iframe 而不是渲染为文本。
     */
    public List<Map<String, Object>> loadHistoryForUi(String uiSessionId) {
        return repo.findByUiSessionIdOrderByCreatedAtAsc(uiSessionId)
                .stream()
                .<Map<String, Object>>map(e -> {
                    if ("widget".equals(e.getRole())) {
                        // widget 消息：携带原始 JSON，前端重建 iframe
                        // 若已标记 processed，同步传出 processed:true 供前端显示已处理卡片
                        String json = e.getContent();
                        boolean processed = json != null && json.contains("\"processed\":true");
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("role", "widget");
                        m.put("widgetJson", json);
                        if (processed) m.put("processed", true);
                        return m;
                    }
                    return Map.of("role", e.getRole(), "content", e.getContent());
                })
                .toList();
    }

    /** 删除某 agent session 的全部持久化消息（清空对话历史）。 */
    @Transactional
    public void clearHistory(String agentSessionId) {
        repo.deleteByAgentSessionId(agentSessionId);
    }

    /**
     * 删除某 ui session 最后 N 条消息（含对应 agent session 中的记录）。
     * 用于重问时替换旧对话——先删 DB 末尾记录，再重新发送。
     */
    @Transactional
    public void deleteLastN(String uiSessionId, String agentSessionId, int n) {
        deleteRange(uiSessionId, agentSessionId, -1, n); // -1 表示从末尾算
    }

    /**
     * 删除某 ui/agent session 最后一个完整 user-turn 的全部消息。
     * <p>"最后一个 user-turn" = 从最后一条 role=user 的消息开始到列表末尾。
     * 适用于重问时清理：无论是普通回复（user+assistant=2条）还是工具调用
     * （user+tool_call+tool_result=3条），都能一并清除，避免孤立的 user 消息
     * 留在 LLM 上下文里导致重问后 LLM 用文字代替工具响应。
     */
    @Transactional
    public void deleteLastTurn(String uiSessionId, String agentSessionId) {
        deleteLastTurnInList(repo.findByUiSessionIdOrderByCreatedAtAsc(uiSessionId));
        if (agentSessionId != null && !agentSessionId.equals(uiSessionId)) {
            deleteLastTurnInList(repo.findByAgentSessionIdOrderByCreatedAtAsc(agentSessionId));
        }
    }

    private void deleteLastTurnInList(List<SessionMessageEntity> all) {
        // 从末尾找到最后一条 role=user 的位置，从那里删到末尾
        int lastUserIdx = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if ("user".equals(all.get(i).getRole())) { lastUserIdx = i; break; }
        }
        if (lastUserIdx < 0) return;
        List<Long> ids = all.subList(lastUserIdx, all.size())
                .stream().map(SessionMessageEntity::getId).toList();
        if (!ids.isEmpty()) repo.deleteAllById(ids);
    }

    /**
     * 删除某 ui session 从第 from 条（0-based）开始共 count 条消息。
     * from=-1 时表示从末尾删 count 条（等同于原 deleteLastN 逻辑）。
     * 同步删除对应 agent session 中同位置的消息以保持 LLM 上下文一致。
     */
    @Transactional
    public void deleteRange(String uiSessionId, String agentSessionId, int from, int count) {
        deleteRangeInList(repo.findByUiSessionIdOrderByCreatedAtAsc(uiSessionId), from, count);
        if (agentSessionId != null && !agentSessionId.equals(uiSessionId)) {
            deleteRangeInList(repo.findByAgentSessionIdOrderByCreatedAtAsc(agentSessionId), from, count);
        }
    }

    private void deleteRangeInList(List<SessionMessageEntity> all, int from, int count) {
        int size = all.size();
        if (size == 0 || count <= 0) return;
        int start = (from < 0) ? Math.max(0, size - count) : Math.min(from, size);
        int end   = Math.min(start + count, size);
        if (start >= end) return;
        List<Long> ids = all.subList(start, end).stream()
                .map(SessionMessageEntity::getId).toList();
        if (!ids.isEmpty()) repo.deleteAllById(ids);
    }
}
