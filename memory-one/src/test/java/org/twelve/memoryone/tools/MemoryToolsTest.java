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
    }

    // ── memory_load ───────────────────────────────────────────────────────

    @Test
    @DisplayName("memory_load: 调用 loader.loadWithIds() 并返回 memory_context")
    void memoryLoad_returnsContext() {
        when(loader.loadWithIds(any(), any(), any(), any(), any()))
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
