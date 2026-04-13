package org.twelve.memoryone.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.twelve.memoryone.loader.DefaultMemoryLoader;
import org.twelve.memoryone.loader.MemoryLoadResult;
import org.twelve.memoryone.model.*;
import org.twelve.memoryone.store.JdbcMemoryStore;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MemoryTools 单元测试（Mock JdbcMemoryStore）。
 */
@DisplayName("MemoryTools 工具方法")
class MemoryToolsTest {

    private JdbcMemoryStore    store;
    private DefaultMemoryLoader loader;
    private MemoryTools        tools;

    @BeforeEach
    void setUp() throws Exception {
        store  = mock(JdbcMemoryStore.class);
        loader = mock(DefaultMemoryLoader.class);
        tools  = new MemoryTools();
        setField(tools, "store",  store);
        setField(tools, "loader", loader);
        when(store.loadSnapshot(anyString(), anyString())).thenReturn(Optional.empty());
    }

    // ── memory_load ───────────────────────────────────────────────────────

    @Test
    @DisplayName("memory_load: 子 session 走 loadSessionContext 并返回 memory_context")
    void memoryLoad_returnsContext() {
        when(loader.loadSessionContext(any(), any(), any(), any(), any()))
                .thenReturn(new MemoryLoadResult("## Agent Memory\n- [FACT] 测试", List.of()));

        Map<String, Object> result = tools.load(
                Map.of("user_message", "用户消息"),
                Map.of("userId", "user1", "sessionId", "sess1"));

        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("memory_context").toString()).contains("Agent Memory");
    }

    @Test
    @DisplayName("memory_load: 无记忆时返回空字符串")
    void memoryLoad_emptyMemory_returnsEmpty() {
        when(loader.loadWithIds(any(), any(), any(), any(), any()))
                .thenReturn(MemoryLoadResult.EMPTY);

        Map<String, Object> result = tools.load(Map.of(), Map.of());
        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("memory_context").toString()).isEmpty();
    }

    // ── memory_query ──────────────────────────────────────────────────────

    @Test
    @DisplayName("memory_query: 调用 store.query() 并返回记忆列表")
    void memoryQuery_returnsList() {
        Memory m = sampleMemory(MemoryType.SEMANTIC, MemoryScope.GLOBAL, "事实内容");
        when(store.query(any())).thenReturn(List.of(m));

        Map<String, Object> result = tools.query(Map.of(), Map.of());
        assertThat(result).containsKey("memories");
        assertThat((List<?>) result.get("memories")).hasSize(1);
    }

    // ── memory_create ─────────────────────────────────────────────────────

    @Test
    @DisplayName("memory_create: 调用 store.save() 并返回 ok:true")
    void memoryCreate_savesAndReturns() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = tools.create(
                Map.of("content", "用户是 Java 专家", "type", "SEMANTIC", "scope", "GLOBAL"),
                Map.of("userId", "default"));

        assertThat(result).containsEntry("ok", true);
        verify(store).save(argThat(m ->
                m.content().equals("用户是 Java 专家") && m.type() == MemoryType.SEMANTIC));
    }

    @Test
    @DisplayName("memory_create: content 缺失返回错误")
    void memoryCreate_missingContent_error() {
        Map<String, Object> result = tools.create(Map.of("type", "SEMANTIC"), Map.of());
        assertThat(result).containsEntry("ok", false);
        verify(store, never()).save(any());
    }

    // ── memory_update ─────────────────────────────────────────────────────

    @Test
    @DisplayName("memory_update: 找到旧 memory 后调用 supersede()")
    void memoryUpdate_callsSupersede() {
        UUID id = UUID.randomUUID();
        when(store.findById(id)).thenReturn(Optional.of(
                sampleMemoryWithId(id, MemoryType.SEMANTIC, MemoryScope.GLOBAL, "旧内容")));
        when(store.supersede(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        Map<String, Object> result = tools.update(Map.of("id", id.toString(), "content", "新内容"), Map.of());

        assertThat(result).containsEntry("ok", true);
        verify(store).supersede(eq(id), argThat(m -> m.content().equals("新内容")));
    }

    // ── memory_delete ─────────────────────────────────────────────────────

    @Test
    @DisplayName("memory_delete: 调用 store.supersede() 软删除")
    void memoryDelete_softDeletesViaSupersede() {
        UUID id = UUID.randomUUID();
        when(store.findById(id)).thenReturn(Optional.of(
                sampleMemoryWithId(id, MemoryType.SEMANTIC, MemoryScope.GLOBAL, "内容")));
        when(store.supersede(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        Map<String, Object> result = tools.delete(Map.of("id", id.toString()), Map.of());

        assertThat(result).containsEntry("ok", true);
        verify(store).supersede(eq(id), argThat(m -> "[deleted]".equals(m.content())));
    }

    // ── memory_delete_request（确认流程保护）─────────────────────────────

    /**
     * deleteRequest 是删除操作的第一步：展示确认框，不执行实际删除。
     * 必须返回 status=awaiting_confirmation 和 canvas.widget_type=sys.confirm，
     * 否则 GenericAgentLoop 无法识别并会让 LLM 继续生成「已删除」回复。
     */
    @Test
    @DisplayName("deleteRequest: 找到匹配记忆时返回 awaiting_confirmation + sys.confirm")
    void deleteRequest_foundMemories_returnsConfirmWidget() {
        UUID id = UUID.randomUUID();
        when(store.query(any())).thenReturn(List.of(
                sampleMemoryWithId(id, MemoryType.SEMANTIC, MemoryScope.GLOBAL, "今天发了工资")));

        Map<String, Object> result = tools.deleteRequest(
                Map.of("keyword", "工资"), Map.of("userId", "user1"));

        // 1. 状态必须是 awaiting_confirmation（供 GenericAgentLoop 检测）
        assertThat(result).containsEntry("ok", true);
        assertThat(result).containsEntry("status", "awaiting_confirmation");

        // 2. canvas.widget_type 必须是 sys.confirm（供前端渲染）
        @SuppressWarnings("unchecked")
        Map<String, Object> canvas = (Map<String, Object>) result.get("canvas");
        assertThat(canvas).isNotNull();
        assertThat(canvas).containsEntry("widget_type", "sys.confirm");
        assertThat(canvas).containsEntry("action", "open");

        // 3. data.yes.tool 必须是 memory_delete_confirmed（点确认后才真正删除）
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) canvas.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> yes = (Map<String, Object>) data.get("yes");
        assertThat(yes).containsEntry("tool", "memory_delete_confirmed");

        // 4. 此时不应触发任何实际删除
        verify(store, never()).supersede(any(), any());
    }

    @Test
    @DisplayName("deleteRequest: 未找到记忆时返回 ok=false（不弹确认框）")
    void deleteRequest_noMemories_returnsError() {
        when(store.query(any())).thenReturn(List.of());

        Map<String, Object> result = tools.deleteRequest(
                Map.of("keyword", "不存在的内容"), Map.of());

        assertThat(result).containsEntry("ok", false);
        assertThat(result).doesNotContainKey("canvas");
        assertThat(result).doesNotContainKey("status");
    }

    @Test
    @DisplayName("deleteRequest: message 不得含「删除成功/已删除」等完成语（防止 LLM 误读）")
    void deleteRequest_messageDoesNotImplyCompletion() {
        UUID id = UUID.randomUUID();
        when(store.query(any())).thenReturn(List.of(
                sampleMemoryWithId(id, MemoryType.SEMANTIC, MemoryScope.GLOBAL, "内容")));

        Map<String, Object> result = tools.deleteRequest(
                Map.of("keyword", "内容"), Map.of());

        String msg = result.getOrDefault("message", "").toString();
        // 确保不包含操作完成含义（防止 LLM 解读为已执行删除）
        assertThat(msg).doesNotContainIgnoringCase("删除成功");
        assertThat(msg).doesNotContainIgnoringCase("已成功删除");
        // 消息里必须表明尚未执行
        assertThat(msg).containsAnyOf("尚未执行", "等待用户确认", "请勿", "未执行");
    }

    // ── memory_delete_confirmed ───────────────────────────────────────────

    @Test
    @DisplayName("deleteConfirmed: 给定 ids 列表时执行软删除")
    void deleteConfirmed_softDeletesAllIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(store.findById(id1)).thenReturn(Optional.of(
                sampleMemoryWithId(id1, MemoryType.SEMANTIC, MemoryScope.GLOBAL, "内容1")));
        when(store.findById(id2)).thenReturn(Optional.of(
                sampleMemoryWithId(id2, MemoryType.SEMANTIC, MemoryScope.GLOBAL, "内容2")));
        when(store.supersede(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        Map<String, Object> result = tools.deleteConfirmed(
                Map.of("ids", List.of(id1.toString(), id2.toString())), Map.of());

        assertThat(result).containsEntry("ok", true);
        assertThat(result).containsEntry("deleted_count", 2);
        verify(store, times(2)).supersede(any(), argThat(m -> "[deleted]".equals(m.content())));
    }

    @Test
    @DisplayName("deleteConfirmed: ids 为空时返回错误（不执行任何操作）")
    void deleteConfirmed_emptyIds_returnsError() {
        Map<String, Object> result = tools.deleteConfirmed(Map.of(), Map.of());

        assertThat(result).containsEntry("ok", false);
        verify(store, never()).supersede(any(), any());
    }

    // ── memory_set_instruction ────────────────────────────────────────────

    @Test
    @DisplayName("memory_set_instruction: 存储 PROCEDURAL memory，tag=memory_instruction")
    void setInstruction_savesProceduralWithTag() {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = tools.setInstruction(
                Map.of("content", "记录每次打开的界面", "scope", "GLOBAL"),
                Map.of("userId", "default"));

        assertThat(result).containsEntry("ok", true);
        verify(store).save(argThat(m ->
                m.type() == MemoryType.PROCEDURAL
                && m.scope() == MemoryScope.GLOBAL
                && m.tags().contains("memory_instruction")
                && m.horizon() == MemoryHorizon.LONG_TERM));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Memory sampleMemory(MemoryType type, MemoryScope scope, String content) {
        return sampleMemoryWithId(UUID.randomUUID(), type, scope, content);
    }

    private Memory sampleMemoryWithId(UUID id, MemoryType type, MemoryScope scope, String content) {
        Instant now = Instant.now();
        return new Memory(id, type, scope, "memory-one", "default", null, null,
            content, null, List.of(), 0.7f, 0.9f,
            MemorySource.USER_STATED, MemoryHorizon.MEDIUM_TERM,
            null, null, null,
            now, now, now, 0, null, null, List.of(), List.of(), List.of());
    }

    private static void setField(Object obj, String name, Object val) throws Exception {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, val);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new RuntimeException("Field not found: " + name);
    }
}
