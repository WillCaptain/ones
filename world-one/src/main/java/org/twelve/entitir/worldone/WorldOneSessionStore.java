package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.twelve.entitir.ontology.llm.LLMConfig;
import org.twelve.entitir.worldone.skills.SkillRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 会话 → {@link GenericAgentLoop} 映射。
 *
 * <p>每个 HTTP 会话（由前端 session_id / agentSessionId 标识）对应一个独立的
 * GenericAgentLoop，保持独立的对话历史。
 *
 * <p>当 worldone 重启后，首次访问某 agentSessionId 时会从
 * {@link MessageHistoryStore} 恢复历史消息，使 LLM 能继续之前的对话。
 */
@Component
public class WorldOneSessionStore {

    @Autowired
    private WorldOneConfigStore configStore;

    @Autowired
    private AppRegistry registry;

    @Autowired
    private MessageHistoryStore messageHistory;

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private DebugFlags debugFlags;

    private final Map<String, GenericAgentLoop> sessions = new ConcurrentHashMap<>();

    public String newSession() {
        String id = UUID.randomUUID().toString();
        sessions.put(id, createLoop(id));
        return id;
    }

    /**
     * 获取或创建 GenericAgentLoop（双键隔离版本）。
     * 若是首次创建（如重启后），从数据库恢复 {@code (agentSessionId, uiSessionId)} 双键过滤后的历史消息。
     *
     * <p><b>必须使用本方法</b>：旧的单键 {@code get(agentSessionId)} 已被移除，
     * 因为按 agent 单键加载会把多个 ui session 的历史一并捞回，污染 LLM 上下文。
     */
    public GenericAgentLoop get(String agentSessionId, String uiSessionId) {
        return sessions.computeIfAbsent(agentSessionId, id -> {
            GenericAgentLoop loop = createLoop(id);
            List<Map<String, Object>> history = messageHistory.loadHistory(id, uiSessionId);
            if (!history.isEmpty()) {
                loop.restoreHistory(history);
            }
            return loop;
        });
    }

    /** 配置变更后调用：清空旧 loop，下次 get() 会用新配置重建并从 DB 恢复历史。 */
    public void invalidateAll() {
        sessions.clear();
    }

    /**
     * 重置指定 session：删除 DB 历史消息，移除内存 loop。
     * 下次 get() 会重建一个全新的 loop（含最新 system prompt，无历史消息）。
     */
    public void resetSession(String agentSessionId) {
        messageHistory.clearHistory(agentSessionId);
        sessions.remove(agentSessionId);
    }

    /**
     * 从内存中的 GenericAgentLoop 末尾截掉最后 n 条历史消息。
     * 配合 deleteLastN DB 操作，保持内存和 DB 一致。
     */
    public void trimHistory(String agentSessionId, int n) {
        GenericAgentLoop loop = sessions.get(agentSessionId);
        if (loop != null) loop.trimHistory(n);
    }

    /**
     * 从内存 GenericAgentLoop 中删除从第 from 条（0-based）开始共 count 条。
     * from=-1 时等同于 trimHistory（从末尾删 count 条）。
     */
    public void trimHistoryRange(String agentSessionId, int from, int count) {
        GenericAgentLoop loop = sessions.get(agentSessionId);
        if (loop != null) loop.trimHistoryRange(from, count);
    }

    /** 删除最后一个完整 user-turn（从最后一条 user 消息到末尾）。 */
    public void trimLastTurn(String agentSessionId) {
        GenericAgentLoop loop = sessions.get(agentSessionId);
        if (loop != null) loop.trimLastTurn();
    }

    public Map<String, String> listSessions() {
        Map<String, String> result = new LinkedHashMap<>();
        sessions.forEach((id, loop) -> result.put(id, "会话 " + id.substring(0, 8)));
        return result;
    }

    private GenericAgentLoop createLoop(String sessionId) {
        return createLoop(sessionId, "default");
    }

    private GenericAgentLoop createLoop(String sessionId, String userId) {
        LLMConfig cfg = LLMConfig.builder()
                .apiKey(configStore.apiKey())
                .baseUrl(configStore.baseUrl())
                .model(configStore.model())
                .timeoutSeconds(configStore.timeout())
                .build();
        return new GenericAgentLoop(sessionId, userId, cfg, registry, skillRegistry, debugFlags);
    }
}
