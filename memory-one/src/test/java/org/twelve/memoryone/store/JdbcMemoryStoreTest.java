package org.twelve.memoryone.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.twelve.memoryone.db.MemoryRepository;
import org.twelve.memoryone.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JdbcMemoryStore 集成测试（@DataJpaTest, H2 内存数据库）。
 */
@ExtendWith(SpringExtension.class)
@DataJpaTest
@Import(JdbcMemoryStore.class)
@DisplayName("JdbcMemoryStore CRUD + 高级操作")
class JdbcMemoryStoreTest {

    @Autowired private JdbcMemoryStore  store;
    @Autowired private MemoryRepository repo;
    @Autowired private TestEntityManager em;

    private static final String AGENT = "memory-one";

    @Test
    @DisplayName("save: 新 memory 可通过 findById 查到")
    void save_findById_found() {
        Memory m = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "事实内容", 0.7f);
        store.save(m);
        em.flush(); em.clear();

        Optional<Memory> found = store.findById(m.id());
        assertThat(found).isPresent();
        assertThat(found.get().content()).isEqualTo("事实内容");
    }

    @Test
    @DisplayName("save: type/scope/horizon 均正确持久化")
    void save_fields_persisted() {
        Memory m = newMemory(MemoryType.PROCEDURAL, MemoryScope.GLOBAL, "用简洁风格", 0.95f);
        store.save(m);
        em.flush(); em.clear();

        Memory found = store.findById(m.id()).orElseThrow();
        assertThat(found.type()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(found.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(found.horizon()).isEqualTo(MemoryHorizon.LONG_TERM);
        assertThat(found.importance()).isEqualTo(0.95f);
        assertThat(found.source()).isEqualTo(MemorySource.USER_STATED);
    }

    @Test
    @DisplayName("findById: 不存在 ID 返回 empty")
    void findById_missing_empty() {
        assertThat(store.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("query: 按 type 过滤")
    void query_filterByType() {
        store.save(newMemory(MemoryType.SEMANTIC,   MemoryScope.GLOBAL, "语义事实", 0.6f));
        store.save(newMemory(MemoryType.PROCEDURAL, MemoryScope.GLOBAL, "约定",    0.9f));
        em.flush(); em.clear();

        List<Memory> result = store.query(
                org.twelve.memoryone.query.MemoryQuery.forAgent(AGENT).types(MemoryType.SEMANTIC).build());
        assertThat(result).allMatch(m -> m.type() == MemoryType.SEMANTIC);
    }

    @Test
    @DisplayName("query: 已 supersede 的 memory 不在结果中")
    void query_supersededMemory_notReturned() {
        Memory old = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "旧内容", 0.7f);
        store.save(old);
        em.flush();

        store.supersede(old.id(), old.withContent("新内容"));
        em.flush(); em.clear();

        List<Memory> result = store.query(
                org.twelve.memoryone.query.MemoryQuery.forAgent(AGENT).types(MemoryType.SEMANTIC).build());
        assertThat(result).extracting(Memory::content).doesNotContain("旧内容");
        assertThat(result).extracting(Memory::content).contains("新内容");
    }

    @Test
    @DisplayName("supersede: 旧 memory 被标记 supersededBy=新 ID")
    void supersede_oldMemory_markedSuperseded() {
        Memory old   = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "旧", 0.6f);
        store.save(old);
        em.flush();

        Memory newer = old.withContent("新");
        store.supersede(old.id(), newer);
        em.flush(); em.clear();

        assertThat(store.findById(old.id()).orElseThrow().supersededBy()).isEqualTo(newer.id());
    }

    @Test
    @DisplayName("promote: 创建 GLOBAL scope 副本")
    void promote_createsGlobalCopy() {
        Memory session = newSessionMemory("会话内容", 0.7f);
        store.save(session);
        em.flush();

        Memory promoted = store.promote(session.id(), MemoryScope.GLOBAL);
        em.flush(); em.clear();

        assertThat(promoted.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(promoted.content()).isEqualTo("会话内容");
    }

    @Test
    @DisplayName("addLink: 关联持久化")
    void addLink_persisted() {
        Memory from = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "原因", 0.6f);
        Memory to   = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "结果", 0.6f);
        store.save(from);
        store.save(to);
        em.flush();

        store.addLink(from.id(), new MemoryLink(to.id(), LinkType.CAUSES, 0.9f));
        em.flush(); em.clear();

        Memory reloaded = store.findById(from.id()).orElseThrow();
        assertThat(reloaded.linkedTo())
                .anyMatch(l -> l.targetId().equals(to.id()) && l.linkType() == LinkType.CAUSES);
    }

    @Test
    @DisplayName("markContradiction: 双向标记")
    void markContradiction_bidirectional() {
        Memory m1 = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "事实A", 0.7f);
        Memory m2 = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "事实B", 0.7f);
        store.save(m1); store.save(m2);
        em.flush();

        store.markContradiction(m1.id(), m2.id());
        em.flush(); em.clear();

        assertThat(store.findById(m1.id()).orElseThrow().contradicts()).contains(m2.id());
        assertThat(store.findById(m2.id()).orElseThrow().contradicts()).contains(m1.id());
    }

    @Test
    @DisplayName("recordAccess: accessCount 增加")
    void recordAccess_updatesMetadata() {
        Memory m = newMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "内容", 0.6f);
        store.save(m);
        em.flush(); em.clear();

        store.recordAccess(List.of(m.id()));
        em.flush(); em.clear();

        assertThat(store.findById(m.id()).orElseThrow().accessCount()).isGreaterThan(0);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Memory newMemory(MemoryType type, MemoryScope scope, String content, float importance) {
        Instant now = Instant.now();
        MemoryHorizon horizon = type == MemoryType.PROCEDURAL && scope == MemoryScope.GLOBAL
                ? MemoryHorizon.LONG_TERM : MemoryHorizon.MEDIUM_TERM;
        return new Memory(UUID.randomUUID(), type, scope,
            AGENT, "default", null, null,
            content, null, List.of(),
            importance, 0.9f, MemorySource.USER_STATED, horizon,
            null, null, null,
            now, now, now, 0, null, null, List.of(), List.of(), List.of());
    }

    private Memory newSessionMemory(String content, float importance) {
        Instant now = Instant.now();
        return new Memory(UUID.randomUUID(), MemoryType.SEMANTIC, MemoryScope.SESSION,
            AGENT, "default", null, "test-session",
            content, null, List.of(),
            importance, 0.9f, MemorySource.USER_STATED, MemoryHorizon.SHORT_TERM,
            null, null, null,
            now, now, now, 0, null, null, List.of(), List.of(), List.of());
    }
}
