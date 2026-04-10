package org.twelve.entitir.worldone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.twelve.entitir.ontology.llm.LLMConfig;

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

    private final Map<String, GenericAgentLoop> sessions = new ConcurrentHashMap<>();

    public String newSession() {
        String id = UUID.randomUUID().toString();
        sessions.put(id, createLoop(id));
        return id;
    }

    /**
     * 获取或创建 GenericAgentLoop。
     * 若是首次创建（如重启后），从数据库恢复历史消息。
     */
    public GenericAgentLoop get(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> {
            GenericAgentLoop loop = createLoop(id);
            List<Map<String, Object>> history = messageHistory.loadHistory(id);
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
        return new GenericAgentLoop(sessionId, userId, cfg, registry);
    }
}
