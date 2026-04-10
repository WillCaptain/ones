package org.twelve.memoryone.agent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.twelve.memoryone.db.MemoryRepository;
import org.twelve.memoryone.loader.DefaultMemoryLoader;
import org.twelve.memoryone.model.*;
import org.twelve.memoryone.store.JdbcMemoryStore;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Memory 生成全场景测试。
 *
 * <p>覆盖维度：
 * <ul>
 *   <li><b>5 种 MemoryType</b>：SEMANTIC / EPISODIC / RELATIONAL / PROCEDURAL / GOAL</li>
 *   <li><b>3 种 MemoryScope</b>：GLOBAL / WORKSPACE / SESSION</li>
 *   <li><b>3 种 MemoryHorizon</b>：LONG_TERM / MEDIUM_TERM / SHORT_TERM（及推断规则）</li>
 *   <li><b>3 种 MemorySource</b>：USER_STATED / INFERRED / SYSTEM</li>
 *   <li><b>6 种操作</b>：CREATE / SUPERSEDE / PROMOTE / GOAL_PROGRESS / LINK / MARK_CONTRADICTION</li>
 *   <li><b>生命周期规则</b>：EPISODIC 仅追加；SEMANTIC 更新必须 SUPERSEDE；GOAL 用 GOAL_PROGRESS</li>
 *   <li><b>用户隔离</b>：不同 userId 的记忆互不可见</li>
 * </ul>
 *
 * <p>通过反射调用 {@link LLMMemoryConsolidator#executeOp}（私有方法），
 * 绕过 LLM，直接验证操作执行逻辑。
 */
@ExtendWith(SpringExtension.class)
@DataJpaTest
@Import({JdbcMemoryStore.class, DefaultMemoryLoader.class})
@DisplayName("Memory 生成全场景测试")
class MemoryGenerationScenariosTest {

    @Autowired private JdbcMemoryStore store;
    @Autowired private DefaultMemoryLoader loader;
    @Autowired private TestEntityManager em;
    @Autowired private MemoryRepository repo;

    private static final String AGENT     = "worldone";
    private static final String USER_A    = "alice";
    private static final String USER_B    = "bob";
    private static final String SESSION   = "session-001";
    private static final String WORKSPACE = "ws-test-001";

    private LLMMemoryConsolidator consolidator;

    @BeforeEach
    void setUp() throws Exception {
        consolidator = new LLMMemoryConsolidator();
        setField(consolidator, "store",        store);
        setField(consolidator, "memoryLoader", loader);
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 1：5 种 MemoryType 的 CREATE 验证
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 1：5 种 MemoryType CREATE")
    class MemoryTypeCreate {

        /**
         * 场景：用户说"我是一名高级 Java 软件工程师"
         * 期望：SEMANTIC / GLOBAL / LONG_TERM（事实，importance>0.8 → LONG_TERM）
         */
        @Test
        @DisplayName("SEMANTIC GLOBAL LONG_TERM — 用户职业事实")
        void create_semantic_global_longTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",         "CREATE",
                "type",       "SEMANTIC",
                "scope",      "GLOBAL",
                "horizon",    "LONG_TERM",
                "content",    "用户是一名高级 Java 软件工程师",
                "importance", 0.9f,
                "confidence", 0.95f,
                "source",     "USER_STATED",
                "tags",       List.of("profile", "occupation")
            ));
            flush();

            List<Memory> result = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            assertThat(result).hasSize(1);
            Memory m = result.get(0);
            assertThat(m.content()).contains("高级 Java 软件工程师");
            assertThat(m.type()).isEqualTo(MemoryType.SEMANTIC);
            assertThat(m.scope()).isEqualTo(MemoryScope.GLOBAL);
            assertThat(m.horizon()).isEqualTo(MemoryHorizon.LONG_TERM);
            assertThat(m.source()).isEqualTo(MemorySource.USER_STATED);
            assertThat(m.importance()).isGreaterThan(0.8f);
            assertThat(m.tags()).contains("profile", "occupation");
        }

        /**
         * 场景：用户今天完成了 Employee 实体设计
         * 期望：EPISODIC / SESSION / SHORT_TERM（事件不可覆盖）
         */
        @Test
        @DisplayName("EPISODIC SESSION SHORT_TERM — 对话事件记录")
        void create_episodic_session_shortTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",         "CREATE",
                "type",       "EPISODIC",
                "scope",      "SESSION",
                "horizon",    "SHORT_TERM",
                "content",    "用户在本次会话中完成了 Employee 实体的字段设计",
                "importance", 0.5f,
                "confidence", 0.9f,
                "source",     "SYSTEM"
            ));
            flush();

            List<Memory> result = store.query(queryAll(USER_A, MemoryType.EPISODIC, MemoryScope.SESSION));
            assertThat(result).hasSize(1);
            Memory m = result.get(0);
            assertThat(m.type()).isEqualTo(MemoryType.EPISODIC);
            assertThat(m.scope()).isEqualTo(MemoryScope.SESSION);
            assertThat(m.horizon()).isEqualTo(MemoryHorizon.SHORT_TERM);
            assertThat(m.source()).isEqualTo(MemorySource.SYSTEM);
        }

        /**
         * 场景：张三和李四是同事
         * 期望：RELATIONAL / WORKSPACE / MEDIUM_TERM
         */
        @Test
        @DisplayName("RELATIONAL WORKSPACE MEDIUM_TERM — 实体关系")
        void create_relational_workspace_mediumTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",         "CREATE",
                "type",       "RELATION",
                "scope",      "WORKSPACE",
                "horizon",    "MEDIUM_TERM",
                "content",    "张三与李四是同事关系，同属技术部",
                "importance", 0.7f,
                "confidence", 0.85f,
                "source",     "INFERRED",
                "tags",       List.of("people", "org-structure")
            ));
            flush();

            List<Memory> result = store.query(queryAll(USER_A, MemoryType.RELATION, MemoryScope.WORKSPACE));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).horizon()).isEqualTo(MemoryHorizon.MEDIUM_TERM);
            assertThat(result.get(0).source()).isEqualTo(MemorySource.INFERRED);
        }

        /**
         * 场景：用户说"以后总是用简洁中文回答"
         * 期望：PROCEDURAL / GLOBAL / LONG_TERM（全局约定固定为长期）
         */
        @Test
        @DisplayName("PROCEDURAL GLOBAL LONG_TERM — 用户偏好约定")
        void create_procedural_global_longTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",         "CREATE",
                "type",       "PROCEDURAL",
                "scope",      "GLOBAL",
                "horizon",    "LONG_TERM",
                "content",    "用户始终希望用简洁中文风格回答，不超过 3 句",
                "importance", 0.95f,
                "confidence", 1.0f,
                "source",     "USER_STATED",
                "tags",       List.of("memory_instruction", "style")
            ));
            flush();

            List<Memory> result = store.query(queryAll(USER_A, MemoryType.PROCEDURAL, MemoryScope.GLOBAL));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).horizon()).isEqualTo(MemoryHorizon.LONG_TERM);
            assertThat(result.get(0).tags()).contains("memory_instruction");
        }

        /**
         * 场景：用户说"我的目标是完成 ERP 系统的实体建模"
         * 期望：GOAL / SESSION / MEDIUM_TERM
         */
        @Test
        @DisplayName("GOAL SESSION MEDIUM_TERM — 用户当前目标")
        void create_goal_session_mediumTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",         "CREATE",
                "type",       "GOAL",
                "scope",      "SESSION",
                "horizon",    "MEDIUM_TERM",
                "content",    "完成 ERP 系统所有核心实体的建模（Employee、Department、Payroll）",
                "importance", 0.9f,
                "confidence", 1.0f,
                "source",     "USER_STATED"
            ));
            flush();

            List<Memory> result = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).type()).isEqualTo(MemoryType.GOAL);
            assertThat(result.get(0).horizon()).isEqualTo(MemoryHorizon.MEDIUM_TERM);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 2：Horizon 推断规则
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 2：Horizon 推断规则（不显式指定 horizon）")
    class HorizonInferenceRules {

        @Test
        @DisplayName("GLOBAL PROCEDURAL → LONG_TERM（全局约定永久保存）")
        void globalProcedural_inferred_longTerm() throws Exception {
            // 不传 horizon，让 inferHorizon 自动推断
            executeOp(USER_A, Map.of(
                "op",      "CREATE",
                "type",    "PROCEDURAL",
                "scope",   "GLOBAL",
                "content", "总是以 Markdown 格式输出代码块",
                "importance", 0.9f,
                "source",  "USER_STATED"
            ));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.PROCEDURAL, MemoryScope.GLOBAL)).get(0);
            assertThat(m.horizon()).isEqualTo(MemoryHorizon.LONG_TERM);
        }

        @Test
        @DisplayName("GLOBAL SEMANTIC importance=0.9 → LONG_TERM")
        void globalSemantic_highImportance_longTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",         "CREATE",
                "type",       "SEMANTIC",
                "scope",      "GLOBAL",
                "content",    "用户名为 Alice，是一位高级架构师",
                "importance", 0.9f,
                "source",     "USER_STATED"
            ));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL)).get(0);
            assertThat(m.horizon()).isEqualTo(MemoryHorizon.LONG_TERM);
        }

        @Test
        @DisplayName("SESSION EPISODIC → SHORT_TERM（会话事件短期）")
        void sessionEpisodic_inferred_shortTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",      "CREATE",
                "type",    "EPISODIC",
                "scope",   "SESSION",
                "content", "用户打开了 Employee 实体编辑面板",
                "importance", 0.4f,
                "source",  "SYSTEM"
            ));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.EPISODIC, MemoryScope.SESSION)).get(0);
            assertThat(m.horizon()).isEqualTo(MemoryHorizon.SHORT_TERM);
        }

        @Test
        @DisplayName("GOAL → 始终 MEDIUM_TERM")
        void goal_always_mediumTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",      "CREATE",
                "type",    "GOAL",
                "scope",   "SESSION",
                "content", "完成 Payroll 实体设计",
                "importance", 0.85f,
                "source",  "USER_STATED"
            ));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION)).get(0);
            assertThat(m.horizon()).isEqualTo(MemoryHorizon.MEDIUM_TERM);
        }

        @Test
        @DisplayName("RELATIONAL WORKSPACE → MEDIUM_TERM")
        void workspaceRelational_inferred_mediumTerm() throws Exception {
            executeOp(USER_A, Map.of(
                "op",      "CREATE",
                "type",    "RELATION",
                "scope",   "WORKSPACE",
                "content", "HR 部门管理 Employee 实体",
                "importance", 0.6f,
                "source",  "INFERRED"
            ));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.RELATION, MemoryScope.WORKSPACE)).get(0);
            assertThat(m.horizon()).isEqualTo(MemoryHorizon.MEDIUM_TERM);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 3：SUPERSEDE — 事实更新规则
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 3：SUPERSEDE 更新规则")
    class SupersedeRules {

        /**
         * 场景：用户先说"我是初级工程师"，后来说"我已升为高级工程师"
         * 规则：SEMANTIC 内容变化 → 必须 SUPERSEDE，禁止重复 CREATE
         */
        @Test
        @DisplayName("SEMANTIC 职位变更 — 旧记忆 supersededBy 新记忆")
        void semantic_careerUpdate_superseded() throws Exception {
            // 建立旧记忆
            executeOp(USER_A, Map.of(
                "op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户是一名初级 Java 工程师",
                "importance", 0.8f, "source", "USER_STATED"
            ));
            flush();
            Memory old = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL)).get(0);

            // 更新为高级工程师
            executeOp(USER_A, Map.of(
                "op",          "SUPERSEDE",
                "old_id",      old.id().toString(),
                "new_content", "用户已晋升为高级 Java 工程师，负责架构设计"
            ));
            flush();

            // 旧记忆被标记 supersededBy
            Memory reloaded = store.findById(old.id()).orElseThrow();
            assertThat(reloaded.supersededBy()).isNotNull();
            assertThat(reloaded.isActive()).isFalse();

            // 新记忆存在
            List<Memory> active = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            assertThat(active).hasSize(1);
            assertThat(active.get(0).content()).contains("高级 Java 工程师");
        }

        /**
         * 场景：用户先说"偏好英文"，后来说"改为中文"
         * 规则：PROCEDURAL 约定变更 → SUPERSEDE
         */
        @Test
        @DisplayName("PROCEDURAL 约定变更 — 语言偏好更新")
        void procedural_preferenceChange_superseded() throws Exception {
            executeOp(USER_A, Map.of(
                "op", "CREATE", "type", "PROCEDURAL", "scope", "GLOBAL",
                "content", "用户偏好英文输出", "importance", 0.9f, "source", "USER_STATED"
            ));
            flush();
            Memory old = store.query(queryAll(USER_A, MemoryType.PROCEDURAL, MemoryScope.GLOBAL)).get(0);

            executeOp(USER_A, Map.of(
                "op",          "SUPERSEDE",
                "old_id",      old.id().toString(),
                "new_content", "用户偏好简洁中文输出，不超过 3 句"
            ));
            flush();

            assertThat(store.findById(old.id()).orElseThrow().isActive()).isFalse();
            List<Memory> active = store.query(queryAll(USER_A, MemoryType.PROCEDURAL, MemoryScope.GLOBAL));
            assertThat(active.get(0).content()).contains("简洁中文");
        }

        /**
         * 规则验证：EPISODIC 禁止 SUPERSEDE。
         * 即使 LLM 错误地发出 SUPERSEDE 指令，旧 EPISODIC 记忆也不应消失。
         * 测试：CREATE 两条 EPISODIC，均应保留（每次都是独立事件）
         */
        @Test
        @DisplayName("EPISODIC 只追加 — 不允许覆盖历史事件")
        void episodic_appendOnly_twoEventsPreserved() throws Exception {
            // 事件1
            executeOp(USER_A, Map.of(
                "op", "CREATE", "type", "EPISODIC", "scope", "SESSION",
                "content", "用户设计了 Employee 实体（gender, age 字段）",
                "importance", 0.5f, "source", "SYSTEM"
            ));
            // 事件2（追加，不覆盖）
            executeOp(USER_A, Map.of(
                "op", "CREATE", "type", "EPISODIC", "scope", "SESSION",
                "content", "用户为 Employee 添加了 salary 字段",
                "importance", 0.5f, "source", "SYSTEM"
            ));
            flush();

            List<Memory> events = store.query(queryAll(USER_A, MemoryType.EPISODIC, MemoryScope.SESSION));
            assertThat(events).hasSize(2);
            assertThat(events).extracting(Memory::content)
                .containsExactlyInAnyOrder(
                    "用户设计了 Employee 实体（gender, age 字段）",
                    "用户为 Employee 添加了 salary 字段");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 4：PROMOTE — SESSION → GLOBAL 升级
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 4：PROMOTE 升级操作")
    class PromoteOp {

        /**
         * 场景：用户在某 session 里说"我叫 Alice"（SESSION 记忆），
         *       之后 Memory Agent 判断这是持久信息，应升为 GLOBAL。
         */
        @Test
        @DisplayName("SESSION SEMANTIC → GLOBAL SEMANTIC 升级")
        void promote_sessionSemantic_toGlobal() throws Exception {
            executeOp(USER_A, Map.of(
                "op", "CREATE", "type", "SEMANTIC", "scope", "SESSION",
                "content", "用户自称 Alice，来自上海",
                "importance", 0.7f, "source", "USER_STATED"
            ));
            flush();
            Memory session = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.SESSION)).get(0);

            executeOp(USER_A, Map.of(
                "op",        "PROMOTE",
                "id",        session.id().toString(),
                "new_scope", "GLOBAL",
                "reason",    "用户基本信息应长期保存"
            ));
            flush();

            // 新 GLOBAL 副本应存在
            List<Memory> globalMems = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            assertThat(globalMems).isNotEmpty();
            assertThat(globalMems.get(0).content()).contains("Alice");
            assertThat(globalMems.get(0).scope()).isEqualTo(MemoryScope.GLOBAL);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 5：GOAL_PROGRESS — 目标进度追加
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 5：GOAL_PROGRESS 目标进度")
    class GoalProgressOp {

        /**
         * 场景：用户有一个长期 GOAL，经过多轮对话逐步完成。
         * 规则：进度用 GOAL_PROGRESS 追加，不 SUPERSEDE 目标本身。
         */
        @Test
        @DisplayName("GOAL 进度追加 — 内容包含进度注释")
        void goalProgress_appendsNote() throws Exception {
            executeOp(USER_A, Map.of(
                "op", "CREATE", "type", "GOAL", "scope", "SESSION",
                "content", "完成 ERP 系统三个核心实体建模：Employee、Department、Payroll",
                "importance", 0.9f, "source", "USER_STATED"
            ));
            flush();
            Memory goal = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION)).get(0);

            // 第一轮进度
            executeOp(USER_A, Map.of(
                "op",           "GOAL_PROGRESS",
                "id",           goal.id().toString(),
                "progress_note","Employee 实体已完成（6 个字段）"
            ));
            flush();

            // 目标记忆应被更新（旧的被 supersede，新的包含进度）
            assertThat(store.findById(goal.id()).orElseThrow().isActive()).isFalse();
            List<Memory> activeGoals = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION));
            assertThat(activeGoals).hasSize(1);
            assertThat(activeGoals.get(0).content()).contains("[进度]").contains("Employee");
        }

        @Test
        @DisplayName("GOAL 多轮进度累积 — 内容保留所有进度记录")
        void goalProgress_multiRound_accumulated() throws Exception {
            executeOp(USER_A, Map.of(
                "op", "CREATE", "type", "GOAL", "scope", "SESSION",
                "content", "完成所有实体设计",
                "importance", 0.9f, "source", "USER_STATED"
            ));
            flush();
            Memory goal = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION)).get(0);

            executeOp(USER_A, Map.of("op", "GOAL_PROGRESS", "id", goal.id().toString(),
                    "progress_note", "Step 1 完成"));
            flush();

            Memory afterStep1 = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION)).get(0);

            executeOp(USER_A, Map.of("op", "GOAL_PROGRESS", "id", afterStep1.id().toString(),
                    "progress_note", "Step 2 完成"));
            flush();

            Memory afterStep2 = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION)).get(0);
            assertThat(afterStep2.content()).contains("Step 2 完成");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 6：LINK + MARK_CONTRADICTION
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 6：LINK 与 MARK_CONTRADICTION")
    class LinkAndContradiction {

        @Test
        @DisplayName("LINK CAUSES — 因果关系建立")
        void link_causes_persisted() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户增加了 salary 字段", "importance", 0.6f, "source", "SYSTEM"));
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "Payroll 计算逻辑需要更新", "importance", 0.7f, "source", "INFERRED"));
            flush();

            List<Memory> mems = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            assertThat(mems).hasSize(2);
            Memory cause = mems.stream().filter(m -> m.content().contains("salary")).findFirst().orElseThrow();
            Memory effect = mems.stream().filter(m -> m.content().contains("Payroll")).findFirst().orElseThrow();

            executeOp(USER_A, Map.of(
                "op",        "LINK",
                "from_id",   cause.id().toString(),
                "to_id",     effect.id().toString(),
                "link_type", "CAUSES",
                "weight",    0.85f
            ));
            flush();

            Memory reloaded = store.findById(cause.id()).orElseThrow();
            assertThat(reloaded.linkedTo())
                .anyMatch(l -> l.targetId().equals(effect.id()) && l.linkType() == LinkType.CAUSES);
        }

        @Test
        @DisplayName("LINK SUPPORTS — 支持关系")
        void link_supports_persisted() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户偏好面向对象设计", "importance", 0.7f, "source", "USER_STATED"));
            executeOp(USER_A, Map.of("op", "CREATE", "type", "PROCEDURAL", "scope", "GLOBAL",
                "content", "总是使用 Java 实体类封装数据", "importance", 0.8f, "source", "INFERRED"));
            flush();

            List<Memory> semantic = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            List<Memory> procedural = store.query(queryAll(USER_A, MemoryType.PROCEDURAL, MemoryScope.GLOBAL));

            executeOp(USER_A, Map.of(
                "op", "LINK",
                "from_id", procedural.get(0).id().toString(),
                "to_id",   semantic.get(0).id().toString(),
                "link_type", "SUPPORTS",
                "weight",  0.9f
            ));
            flush();

            Memory reloaded = store.findById(procedural.get(0).id()).orElseThrow();
            assertThat(reloaded.linkedTo())
                .anyMatch(l -> l.linkType() == LinkType.SUPPORTS);
        }

        @Test
        @DisplayName("MARK_CONTRADICTION — 互相标记矛盾事实")
        void markContradiction_bidirectional() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户偏好微服务架构", "importance", 0.8f, "source", "USER_STATED"));
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户偏好单体架构，认为微服务过于复杂", "importance", 0.8f, "source", "USER_STATED"));
            flush();

            List<Memory> mems = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            assertThat(mems).hasSize(2);

            executeOp(USER_A, Map.of(
                "op",          "MARK_CONTRADICTION",
                "id1",         mems.get(0).id().toString(),
                "id2",         mems.get(1).id().toString(),
                "description", "用户对架构偏好存在矛盾"
            ));
            flush();

            // 双向标记
            assertThat(store.findById(mems.get(0).id()).orElseThrow().contradicts())
                .contains(mems.get(1).id());
            assertThat(store.findById(mems.get(1).id()).orElseThrow().contradicts())
                .contains(mems.get(0).id());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 7：用户隔离
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 7：用户隔离 — 多用户记忆独立")
    class UserIsolation {

        @Test
        @DisplayName("USER_A 和 USER_B 的 SEMANTIC 记忆互不可见")
        void semantic_isolation_acrossUsers() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "Alice 是 Java 工程师", "importance", 0.8f, "source", "USER_STATED"));
            executeOp(USER_B, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "Bob 是产品经理", "importance", 0.8f, "source", "USER_STATED"));
            flush();

            var queryA = org.twelve.memoryone.query.MemoryQuery.forAgent(AGENT).userId(USER_A)
                    .types(MemoryType.SEMANTIC).scopes(MemoryScope.GLOBAL).build();
            var queryB = org.twelve.memoryone.query.MemoryQuery.forAgent(AGENT).userId(USER_B)
                    .types(MemoryType.SEMANTIC).scopes(MemoryScope.GLOBAL).build();

            assertThat(store.query(queryA)).hasSize(1)
                .extracting(Memory::content).containsExactly("Alice 是 Java 工程师");
            assertThat(store.query(queryB)).hasSize(1)
                .extracting(Memory::content).containsExactly("Bob 是产品经理");
        }

        @Test
        @DisplayName("SUPERSEDE 只影响同一用户的记忆")
        void supersede_isolation_doesNotCrossUsers() throws Exception {
            // 两个用户各有一条相同内容的记忆
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户是工程师", "importance", 0.7f, "source", "USER_STATED"));
            executeOp(USER_B, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户是工程师", "importance", 0.7f, "source", "USER_STATED"));
            flush();

            var queryA = org.twelve.memoryone.query.MemoryQuery.forAgent(AGENT).userId(USER_A)
                    .types(MemoryType.SEMANTIC).scopes(MemoryScope.GLOBAL).build();
            Memory memA = store.query(queryA).get(0);

            // 只 SUPERSEDE User A 的记忆
            executeOp(USER_A, Map.of(
                "op", "SUPERSEDE", "old_id", memA.id().toString(),
                "new_content", "Alice 升为架构师"
            ));
            flush();

            // User B 的记忆不受影响
            var queryB = org.twelve.memoryone.query.MemoryQuery.forAgent(AGENT).userId(USER_B)
                    .types(MemoryType.SEMANTIC).scopes(MemoryScope.GLOBAL).build();
            assertThat(store.query(queryB)).hasSize(1)
                .extracting(Memory::content).contains("用户是工程师");
        }

        @Test
        @DisplayName("PROCEDURAL 约定按用户隔离 — 不同偏好互不干扰")
        void procedural_isolation_perUser() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "PROCEDURAL", "scope", "GLOBAL",
                "content", "Alice 偏好英文输出", "importance", 0.9f, "source", "USER_STATED"));
            executeOp(USER_B, Map.of("op", "CREATE", "type", "PROCEDURAL", "scope", "GLOBAL",
                "content", "Bob 偏好中文输出", "importance", 0.9f, "source", "USER_STATED"));
            flush();

            var loadA = loader.load(USER_A, AGENT, null, "");
            var loadB = loader.load(USER_B, AGENT, null, "");

            assertThat(loadA).contains("Alice").doesNotContain("Bob");
            assertThat(loadB).contains("Bob").doesNotContain("Alice");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 8：MemorySource 默认 confidence
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 8：MemorySource 默认 confidence 值")
    class MemorySourceConfidence {

        @Test
        @DisplayName("USER_STATED → confidence ≥ 0.9")
        void userStated_highConfidence() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "用户明确表示自己在上海工作", "importance", 0.7f, "source", "USER_STATED"));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL)).get(0);
            assertThat(m.source()).isEqualTo(MemorySource.USER_STATED);
            assertThat(m.confidence()).isGreaterThanOrEqualTo(0.9f);
        }

        @Test
        @DisplayName("INFERRED → confidence 0.7 左右")
        void inferred_moderateConfidence() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "RELATION", "scope", "WORKSPACE",
                "content", "推断：用户的团队规模较小（<10人）", "importance", 0.5f, "source", "INFERRED"));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.RELATION, MemoryScope.WORKSPACE)).get(0);
            assertThat(m.source()).isEqualTo(MemorySource.INFERRED);
            assertThat(m.confidence()).isGreaterThan(0f).isLessThan(0.9f);
        }

        @Test
        @DisplayName("SYSTEM → confidence = MemorySource.SYSTEM.defaultConfidence()（0.9）")
        void system_fullConfidence() throws Exception {
            executeOp(USER_A, Map.of("op", "CREATE", "type", "EPISODIC", "scope", "SESSION",
                "content", "系统检测到用户打开了 Employee 实体面板", "importance", 0.3f, "source", "SYSTEM"));
            flush();

            Memory m = store.query(queryAll(USER_A, MemoryType.EPISODIC, MemoryScope.SESSION)).get(0);
            assertThat(m.source()).isEqualTo(MemorySource.SYSTEM);
            assertThat(m.confidence()).isEqualTo(MemorySource.SYSTEM.defaultConfidence());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Part 9：完整记忆生命周期场景（综合）
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Part 9：完整生命周期综合场景")
    class FullLifecycleScenario {

        /**
         * 场景模拟：Alice 使用系统完成 ERP 设计的完整对话流。
         *
         * <pre>
         * 第1轮：用户自我介绍 → SEMANTIC/GLOBAL
         * 第2轮：设置偏好 → PROCEDURAL/GLOBAL
         * 第3轮：说明目标 → GOAL/SESSION
         * 第4轮：完成 Employee 实体 → EPISODIC/SESSION + GOAL_PROGRESS
         * 第5轮：更新职位信息 → SUPERSEDE SEMANTIC
         * 第6轮：发现矛盾偏好 → MARK_CONTRADICTION
         * 第7轮：关联事实 → LINK
         * </pre>
         */
        @Test
        @DisplayName("Alice 的 ERP 设计全流程 — 7 轮对话 × 全操作覆盖")
        void alice_erpDesign_fullLifecycle() throws Exception {

            // 第1轮：自我介绍
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "Alice 是一名全栈工程师，擅长 Java 和 Vue3",
                "importance", 0.9f, "source", "USER_STATED",
                "tags", List.of("profile")));
            flush();

            // 第2轮：设置输出偏好
            executeOp(USER_A, Map.of("op", "CREATE", "type", "PROCEDURAL", "scope", "GLOBAL",
                "content", "Alice 偏好中文简洁回复，代码示例用 Java",
                "importance", 0.95f, "source", "USER_STATED",
                "tags", List.of("memory_instruction")));
            flush();

            // 第3轮：声明目标
            executeOp(USER_A, Map.of("op", "CREATE", "type", "GOAL", "scope", "SESSION",
                "content", "完成 ERP 三个核心实体（Employee、Department、Payroll）的建模",
                "importance", 0.9f, "source", "USER_STATED"));
            flush();

            Memory goal = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION)).get(0);
            assertThat(goal).isNotNull();

            // 第4轮：完成 Employee → 事件 + 进度
            executeOp(USER_A, Map.of("op", "CREATE", "type", "EPISODIC", "scope", "SESSION",
                "content", "完成了 Employee 实体设计（8 个字段：id、name、gender、age、dept、salary、level、hire_date）",
                "importance", 0.6f, "source", "SYSTEM"));
            executeOp(USER_A, Map.of("op", "GOAL_PROGRESS", "id", goal.id().toString(),
                "progress_note", "Employee 实体已完成，共 8 个字段"));
            flush();

            // 第5轮：Alice 升职 → SUPERSEDE
            Memory profile = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL)).get(0);
            executeOp(USER_A, Map.of("op", "SUPERSEDE", "old_id", profile.id().toString(),
                "new_content", "Alice 现已升为技术架构师，负责 ERP 系统整体设计"));
            flush();

            // 第6轮：发现矛盾偏好（用户既说要简洁，又要详细文档）
            executeOp(USER_A, Map.of("op", "CREATE", "type", "PROCEDURAL", "scope", "GLOBAL",
                "content", "Alice 希望每次操作后输出完整的设计文档",
                "importance", 0.8f, "source", "USER_STATED"));
            flush();

            List<Memory> procs = store.query(queryAll(USER_A, MemoryType.PROCEDURAL, MemoryScope.GLOBAL));
            assertThat(procs).hasSizeGreaterThanOrEqualTo(2);
            Memory brief = procs.stream().filter(m -> m.content().contains("简洁")).findFirst().orElseThrow();
            Memory detailed = procs.stream().filter(m -> m.content().contains("完整的设计文档")).findFirst().orElseThrow();

            executeOp(USER_A, Map.of("op", "MARK_CONTRADICTION",
                "id1", brief.id().toString(), "id2", detailed.id().toString(),
                "description", "简洁风格与详细文档要求互相矛盾"));
            flush();

            // 第7轮：LINK Employee 完成 → Payroll 需要更新
            executeOp(USER_A, Map.of("op", "CREATE", "type", "SEMANTIC", "scope", "GLOBAL",
                "content", "Payroll 实体需要引用 Employee.salary 字段",
                "importance", 0.7f, "source", "INFERRED"));
            flush();

            List<Memory> semantics = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            Memory payroll = semantics.stream()
                .filter(m -> m.content().contains("Payroll")).findFirst().orElseThrow();
            List<Memory> episodes = store.query(queryAll(USER_A, MemoryType.EPISODIC, MemoryScope.SESSION));
            Memory empEvent = episodes.get(0);

            executeOp(USER_A, Map.of("op", "LINK",
                "from_id", empEvent.id().toString(), "to_id", payroll.id().toString(),
                "link_type", "CAUSES", "weight", 0.8f));
            flush();

            // ── 最终断言 ──────────────────────────────────────────────
            // 活跃 SEMANTIC 只有新的（旧的被 supersede）
            List<Memory> activeSemantics = store.query(queryAll(USER_A, MemoryType.SEMANTIC, MemoryScope.GLOBAL));
            assertThat(activeSemantics).extracting(Memory::content)
                .anyMatch(c -> c.contains("技术架构师"));
            assertThat(store.findById(profile.id()).orElseThrow().isActive()).isFalse();

            // GOAL 包含进度
            List<Memory> goals = store.query(queryAll(USER_A, MemoryType.GOAL, MemoryScope.SESSION));
            assertThat(goals.get(0).content()).contains("[进度]");

            // EPISODIC 事件仍在
            assertThat(store.query(queryAll(USER_A, MemoryType.EPISODIC, MemoryScope.SESSION))).isNotEmpty();

            // 矛盾标记双向
            assertThat(store.findById(brief.id()).orElseThrow().contradicts()).contains(detailed.id());

            // LINK 建立
            assertThat(store.findById(empEvent.id()).orElseThrow().linkedTo())
                .anyMatch(l -> l.linkType() == LinkType.CAUSES);

            // DefaultMemoryLoader 返回非空格式化输出
            String loaded = loader.load(USER_A, AGENT, SESSION, "Employee 字段");
            assertThat(loaded).isNotBlank().contains("## Agent Memory");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private void executeOp(String userId, Map<String, Object> op) throws Exception {
        Method m = LLMMemoryConsolidator.class.getDeclaredMethod(
                "executeOp", String.class, String.class, String.class, String.class, Map.class);
        m.setAccessible(true);
        m.invoke(consolidator, userId, AGENT, SESSION, WORKSPACE, op);
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    private org.twelve.memoryone.query.MemoryQuery queryAll(
            String userId, MemoryType type, MemoryScope scope) {
        return org.twelve.memoryone.query.MemoryQuery.forAgent(AGENT)
                .userId(userId)
                .session(SESSION)
                .workspace(WORKSPACE)
                .types(type)
                .scopes(scope)
                .build();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
