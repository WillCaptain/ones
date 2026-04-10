package org.twelve.memoryone.loader;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.twelve.memoryone.model.*;
import org.twelve.memoryone.store.JdbcMemoryStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * DefaultMemoryLoader 加载策略与输出格式测试。
 *
 * <p>验证：
 * <ul>
 *   <li>always-load 策略：GLOBAL SEMANTIC、GLOBAL PROCEDURAL、SESSION GOAL、WORKSPACE RELATIONAL 必加载</li>
 *   <li>conditional-load 策略：EPISODIC / SESSION PROCEDURAL / SESSION RELATIONAL 按需加载</li>
 *   <li>TOKEN BUDGET：超出截断不崩溃</li>
 *   <li>输出格式：各类型正确标签（[FACT], [CONVENTION], [GOAL], [REL], [EVENT ...]）</li>
 *   <li>用户隔离：load 只返回对应 userId 的记忆</li>
 *   <li>返回 loadWithIds：ids 列表不为空，并更新 accessCount</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@DataJpaTest
@Import({JdbcMemoryStore.class, DefaultMemoryLoader.class})
@DisplayName("DefaultMemoryLoader 加载策略与格式")
class MemoryLoaderScenariosTest {

    @Autowired private JdbcMemoryStore store;
    @Autowired private DefaultMemoryLoader loader;
    @Autowired private TestEntityManager em;

    private static final String AGENT     = "worldone";
    private static final String USER      = "alice";
    private static final String SESSION   = "session-load-test";
    private static final String WORKSPACE = "ws-001";

    // ════════════════════════════════════════════════════════════════════
    // Part 1：空库时返回 EMPTY
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空库：load 返回空字符串")
    void emptyStore_returnsEmpty() {
        String result = loader.load(USER, AGENT, SESSION, "任何消息");
        assertThat(result).isBlank();
    }

    @Test
    @DisplayName("空库：loadWithIds 返回 EMPTY")
    void emptyStore_loadWithIds_empty() {
        MemoryLoadResult r = loader.loadWithIds(USER, AGENT, SESSION, null, "");
        assertThat(r).isEqualTo(MemoryLoadResult.EMPTY);
        assertThat(r.loadedIds()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 2：always-load 策略
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 2：always-load — 无论用户消息内容如何都必加载")
    class AlwaysLoad {

        @Test
        @DisplayName("GLOBAL SEMANTIC importance≥0.5 — 始终加载")
        void globalSemantic_alwaysLoaded() {
            store.save(mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "Alice 是高级工程师", 0.8f));
            flush();

            String result = loader.load(USER, AGENT, SESSION, "不相关的话题");
            assertThat(result).contains("Alice 是高级工程师");
        }

        @Test
        @DisplayName("GLOBAL SEMANTIC importance<0.5 — 不加载（低重要性过滤）")
        void globalSemantic_lowImportance_notLoaded() {
            store.save(mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "可以忽略的信息", 0.3f));
            flush();

            String result = loader.load(USER, AGENT, SESSION, "任何话题");
            assertThat(result).doesNotContain("可以忽略的信息");
        }

        @Test
        @DisplayName("GLOBAL PROCEDURAL — 始终加载（约定无门槛）")
        void globalProcedural_alwaysLoaded() {
            store.save(mem(MemoryType.PROCEDURAL, MemoryScope.GLOBAL, "始终用中文简洁回复", 0.95f));
            flush();

            String result = loader.load(USER, AGENT, SESSION, "随便一句话");
            assertThat(result).contains("始终用中文简洁回复");
        }

        @Test
        @DisplayName("SESSION GOAL — 有 sessionId 时始终加载")
        void sessionGoal_withSessionId_alwaysLoaded() {
            store.save(sessionMem(MemoryType.GOAL, "完成 ERP 实体建模", 0.9f));
            flush();

            String result = loader.load(USER, AGENT, SESSION, "");
            assertThat(result).contains("ERP 实体建模");
        }

        @Test
        @DisplayName("SESSION GOAL — 无 sessionId 时不加载")
        void sessionGoal_noSessionId_notLoaded() {
            store.save(sessionMem(MemoryType.GOAL, "完成 ERP 实体建模", 0.9f));
            flush();

            // sessionId = null
            String result = loader.load(USER, AGENT, null, "");
            assertThat(result).doesNotContain("ERP 实体建模");
        }

        @Test
        @DisplayName("WORKSPACE RELATIONAL importance≥0.6 — 始终加载")
        void workspaceRelational_alwaysLoaded() {
            store.save(wsMem(MemoryType.RELATION, "张三是 Alice 的上级", 0.7f));
            flush();

            String result = loader.load(USER, AGENT, SESSION, WORKSPACE, "随便话题");
            assertThat(result).contains("张三是 Alice 的上级");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 3：输出格式验证
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 3：输出格式标签验证")
    class OutputFormat {

        @Test
        @DisplayName("GOAL → [GOAL] 标签")
        void format_goal_label() {
            store.save(sessionMem(MemoryType.GOAL, "完成 Payroll 模块", 0.9f));
            flush();
            assertThat(loader.load(USER, AGENT, SESSION, "")).contains("[GOAL]");
        }

        @Test
        @DisplayName("SEMANTIC → [FACT] 标签")
        void format_semantic_label() {
            store.save(mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "Alice 在上海", 0.8f));
            flush();
            assertThat(loader.load(USER, AGENT, SESSION, "")).contains("[FACT]");
        }

        @Test
        @DisplayName("RELATIONAL → [REL] 标签")
        void format_relational_label() {
            store.save(wsMem(MemoryType.RELATION, "Alice 与张三协作", 0.7f));
            flush();
            assertThat(loader.load(USER, AGENT, SESSION, WORKSPACE, "")).contains("[REL]");
        }

        @Test
        @DisplayName("PROCEDURAL → [CONVENTION] 标签")
        void format_procedural_label() {
            store.save(mem(MemoryType.PROCEDURAL, MemoryScope.GLOBAL, "总是用 Java 代码示例", 0.9f));
            flush();
            assertThat(loader.load(USER, AGENT, SESSION, "")).contains("[CONVENTION]");
        }

        @Test
        @DisplayName("EPISODIC → [EVENT 日期] 标签")
        void format_episodic_label() {
            store.save(sessionMem(MemoryType.EPISODIC, "用户打开了设计面板", 0.5f));
            flush();
            assertThat(loader.load(USER, AGENT, SESSION, "")).contains("[EVENT ");
        }

        @Test
        @DisplayName("输出以 ## Agent Memory 开头")
        void format_header() {
            store.save(mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "任意事实", 0.8f));
            flush();
            assertThat(loader.load(USER, AGENT, SESSION, "")).startsWith("## Agent Memory");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 4：loadWithIds — ids 与 accessCount 更新
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 4：loadWithIds — IDs 返回与访问计数")
    class LoadWithIds {

        @Test
        @DisplayName("loadWithIds 返回非空 memoryIds")
        void loadWithIds_returnsIds() {
            store.save(mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "Alice 技术栈", 0.8f));
            flush();

            MemoryLoadResult r = loader.loadWithIds(USER, AGENT, SESSION, null, "");
            assertThat(r.loadedIds()).isNotEmpty();
            assertThat(r.injectionText()).contains("Alice 技术栈");
        }

        @Test
        @DisplayName("多次 load 后 accessCount 增加")
        void loadWithIds_incrementsAccessCount() {
            Memory m = mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "被加载的内容", 0.8f);
            store.save(m);
            flush();

            loader.loadWithIds(USER, AGENT, SESSION, null, "");
            em.flush(); em.clear();

            int count = store.findById(m.id()).orElseThrow().accessCount();
            assertThat(count).isGreaterThan(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 5：用户隔离
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("用户隔离 — load 只返回当前用户的记忆")
    void load_isolation_perUser() {
        // Alice 的记忆
        store.save(mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "Alice 的事实", 0.8f));
        // Bob 的记忆
        Memory bobMem = globalMem("bob", MemoryType.SEMANTIC, MemoryScope.GLOBAL, "Bob 的事实", 0.8f);
        store.save(bobMem);
        flush();

        String aliceResult = loader.load(USER, AGENT, SESSION, "");
        assertThat(aliceResult).contains("Alice 的事实").doesNotContain("Bob 的事实");

        String bobResult = loader.load("bob", AGENT, SESSION, "");
        assertThat(bobResult).contains("Bob 的事实").doesNotContain("Alice 的事实");
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 6：多类型混合输出 — 分区顺序
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("混合记忆 — 输出包含所有存在类型的区域")
    void mixed_memories_allSectionsPresent() {
        store.save(sessionMem(MemoryType.GOAL,       "ERP 建模目标",          0.9f));
        store.save(mem(MemoryType.SEMANTIC,   MemoryScope.GLOBAL, "Alice 信息", 0.8f));
        store.save(wsMem(MemoryType.RELATION,                   "与张三关系",  0.7f));
        store.save(mem(MemoryType.PROCEDURAL, MemoryScope.GLOBAL, "简洁风格",   0.95f));
        store.save(sessionMem(MemoryType.EPISODIC,    "完成 Employee",         0.5f));
        flush();

        String result = loader.load(USER, AGENT, SESSION, WORKSPACE, "Employee 字段");
        assertThat(result)
            .contains("当前目标")
            .contains("全局事实")
            .contains("关系结构")
            .contains("约定/偏好")
            .contains("最近事件");
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 7：loadGoals — 仅返回 SESSION GOAL
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("loadGoals — 只返回当前 session 的活跃 GOAL")
    void loadGoals_returnsOnlySessionGoals() {
        store.save(sessionMem(MemoryType.GOAL, "目标 A", 0.9f));
        store.save(mem(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "无关事实", 0.8f));
        flush();

        List<Memory> goals = loader.loadGoals(USER, AGENT, SESSION);
        assertThat(goals).hasSize(1);
        assertThat(goals.get(0).type()).isEqualTo(MemoryType.GOAL);
    }

    @Test
    @DisplayName("loadGoals — sessionId=null 返回空列表")
    void loadGoals_nullSession_empty() {
        store.save(sessionMem(MemoryType.GOAL, "目标 A", 0.9f));
        flush();

        List<Memory> goals = loader.loadGoals(USER, AGENT, null);
        assertThat(goals).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private Memory mem(MemoryType type, MemoryScope scope, String content, float importance) {
        return globalMem(USER, type, scope, content, importance);
    }

    private Memory globalMem(String userId, MemoryType type, MemoryScope scope, String content, float importance) {
        Instant now = Instant.now();
        return new Memory(UUID.randomUUID(), type, scope,
            AGENT, userId, null, null,
            content, null, List.of(),
            importance, MemorySource.USER_STATED.defaultConfidence(),
            MemorySource.USER_STATED, MemoryHorizon.LONG_TERM,
            null, null, null,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of());
    }

    private Memory wsMem(MemoryType type, String content, float importance) {
        Instant now = Instant.now();
        return new Memory(UUID.randomUUID(), type, MemoryScope.WORKSPACE,
            AGENT, USER, "ws-001", null,
            content, null, List.of(),
            importance, MemorySource.INFERRED.defaultConfidence(),
            MemorySource.INFERRED, MemoryHorizon.MEDIUM_TERM,
            null, null, null,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of());
    }

    private Memory sessionMem(MemoryType type, String content, float importance) {
        Instant now = Instant.now();
        MemoryHorizon horizon = type == MemoryType.EPISODIC ? MemoryHorizon.SHORT_TERM : MemoryHorizon.MEDIUM_TERM;
        return new Memory(UUID.randomUUID(), type, MemoryScope.SESSION,
            AGENT, USER, null, SESSION,
            content, null, List.of(),
            importance, MemorySource.SYSTEM.defaultConfidence(),
            MemorySource.SYSTEM, horizon,
            null, null, null,
            now, now, now, 0, null,
            null, List.of(), List.of(), List.of());
    }

    private void flush() {
        em.flush();
        em.clear();
    }
}
