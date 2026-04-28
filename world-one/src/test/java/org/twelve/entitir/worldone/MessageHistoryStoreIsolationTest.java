package org.twelve.entitir.worldone;

import org.junit.jupiter.api.Test;
import org.twelve.entitir.worldone.db.SessionMessageEntity;
import org.twelve.entitir.worldone.db.SessionMessageRepository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双键隔离单元测试：{@link MessageHistoryStore#loadHistory(String, String)}
 * 必须严格按 {@code (agent_session_id, ui_session_id)} 双键过滤，
 * 不允许跨 ui session 的消息进入同一个 LLM 上下文。
 *
 * <p>历史教训：之前 {@code loadHistory(agentSessionId)} 单键加载，导致
 * 旧 task session 误挂在 main agent 名下的消息（不可见的 ui_session_id）
 * 在重启后被一并捞回主 session 的 LLM 上下文，污染对话。
 */
class MessageHistoryStoreIsolationTest {

    @Test
    void loadHistory_filtersByBothAgentAndUiSessionId() throws Exception {
        // 模拟 repo：为 (agent='main', ui='main') 返回主对话；
        // 为 (agent='main', ui='task-X') 返回某个旧 task 子 session 的消息。
        InMemoryRepo repo = new InMemoryRepo();
        repo.add("main", "main",   "user",      "列出所有应用");
        repo.add("main", "main",   "assistant", "已为你打开应用列表");
        repo.add("main", "task-X", "user",      "为 Employee 加生日字段");
        repo.add("main", "task-X", "assistant", "已添加 birthday: String?");

        MessageHistoryStore store = new MessageHistoryStore();
        injectRepo(store, repo);

        List<Map<String, Object>> mainHistory = store.loadHistory("main", "main");

        assertThat(mainHistory).hasSize(2);
        assertThat(mainHistory.get(0)).containsEntry("content", "列出所有应用");
        assertThat(mainHistory.get(1)).containsEntry("content", "已为你打开应用列表");

        // 关键断言：task-X 的消息绝不能进入 main 的上下文。
        assertThat(mainHistory)
                .as("跨 ui session 的消息绝不能进入主 session 的 LLM 上下文")
                .noneMatch(m -> {
                    String c = String.valueOf(m.get("content"));
                    return c.contains("birthday") || c.contains("Employee");
                });
    }

    @Test
    void loadHistory_returnsEmpty_whenUiSessionHasNoMessages() throws Exception {
        InMemoryRepo repo = new InMemoryRepo();
        repo.add("main", "task-X", "user", "孤儿数据");

        MessageHistoryStore store = new MessageHistoryStore();
        injectRepo(store, repo);

        List<Map<String, Object>> mainHistory = store.loadHistory("main", "main");

        assertThat(mainHistory)
                .as("主 session 没有自己的消息时应返回空，不能 fallback 去捞 agent 名下其他 ui 的消息")
                .isEmpty();
    }

    private static void injectRepo(MessageHistoryStore store, SessionMessageRepository repo) throws Exception {
        Field f = MessageHistoryStore.class.getDeclaredField("repo");
        f.setAccessible(true);
        f.set(store, repo);
    }

    /** 极简内存 repo，只覆盖本测试需要的方法。 */
    static class InMemoryRepo implements SessionMessageRepository {
        private final List<SessionMessageEntity> rows = new ArrayList<>();

        void add(String agentId, String uiId, String role, String content) {
            rows.add(new SessionMessageEntity(agentId, uiId, role, content));
        }

        @Override
        public List<SessionMessageEntity> findByAgentSessionIdAndUiSessionIdOrderByCreatedAtAsc(
                String agentId, String uiId) {
            return rows.stream()
                    .filter(r -> agentId.equals(r.getAgentSessionId())
                              && uiId.equals(r.getUiSessionId()))
                    .toList();
        }

        @Override
        public List<SessionMessageEntity> findByAgentSessionIdOrderByCreatedAtAsc(String agentId) {
            return rows.stream().filter(r -> agentId.equals(r.getAgentSessionId())).toList();
        }

        @Override
        public List<SessionMessageEntity> findByUiSessionIdOrderByCreatedAtAsc(String uiId) {
            return rows.stream().filter(r -> uiId.equals(r.getUiSessionId())).toList();
        }

        @Override public List<SessionMessageEntity> findTopNByUiSessionIdOrderByCreatedAtDesc(String uiId) { return List.of(); }
        @Override public void deleteByAgentSessionId(String agentId) { rows.removeIf(r -> agentId.equals(r.getAgentSessionId())); }

        // ── JpaRepository 其余方法均不在测试覆盖范围 ─────────────────────────────
        @Override public <S extends SessionMessageEntity> S save(S e) { rows.add(e); return e; }
        @Override public <S extends SessionMessageEntity> List<S> saveAll(Iterable<S> es) { es.forEach(this::save); var l = new ArrayList<S>(); es.forEach(l::add); return l; }
        @Override public java.util.Optional<SessionMessageEntity> findById(Long id) { return java.util.Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public List<SessionMessageEntity> findAll() { return rows; }
        @Override public List<SessionMessageEntity> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) {}
        @Override public void delete(SessionMessageEntity e) { rows.remove(e); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends SessionMessageEntity> es) { es.forEach(rows::remove); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public List<SessionMessageEntity> findAll(org.springframework.data.domain.Sort sort) { return rows; }
        @Override public org.springframework.data.domain.Page<SessionMessageEntity> findAll(org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public void flush() {}
        @Override public <S extends SessionMessageEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends SessionMessageEntity> List<S> saveAllAndFlush(Iterable<S> es) { return saveAll(es); }
        @Override public void deleteAllInBatch(Iterable<SessionMessageEntity> es) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public SessionMessageEntity getOne(Long id) { return null; }
        @Override public SessionMessageEntity getById(Long id) { return null; }
        @Override public SessionMessageEntity getReferenceById(Long id) { return null; }
        @Override public <S extends SessionMessageEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex) { return List.of(); }
        @Override public <S extends SessionMessageEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { return List.of(); }
        @Override public <S extends SessionMessageEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends SessionMessageEntity> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
        @Override public <S extends SessionMessageEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
        @Override public <S extends SessionMessageEntity> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { return java.util.Optional.empty(); }
        @Override public <S extends SessionMessageEntity, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { return null; }
    }
}
