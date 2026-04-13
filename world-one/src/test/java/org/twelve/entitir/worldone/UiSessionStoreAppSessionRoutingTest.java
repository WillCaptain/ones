package org.twelve.entitir.worldone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.twelve.entitir.worldone.db.UiSessionEntity;
import org.twelve.entitir.worldone.db.UiSessionRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * App session 路由：单实例 {@code app-{appId}} 与多实例 {@code app-{appId}-{uuid(instanceId)}}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UiSessionStore — app session 路由")
class UiSessionStoreAppSessionRoutingTest {

    @Mock
    private UiSessionRepository repo;

    private UiSessionStore store;

    @BeforeEach
    void setUp() {
        store = new UiSessionStore();
        ReflectionTestUtils.setField(store, "repo", repo);
    }

    @Test
    @DisplayName("无 instanceId：幂等 id = app-{appId}")
    void singletonAppSession_usesFixedId() {
        when(repo.findById("app-myapp")).thenReturn(Optional.empty());
        when(repo.save(any(UiSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UiSession ui = store.ensureApp("myapp", "My App", null);

        assertThat(ui.id()).isEqualTo("app-myapp");
        assertThat(ui.agentSessionId()).isEqualTo("app-myapp");
        assertThat(ui.type()).isEqualTo("app");
        verify(repo).save(argThat(e ->
                "app-myapp".equals(e.getId())
                        && "app".equals(e.getType())
                        && e.getCanvasSessionId() == null));
    }

    @Test
    @DisplayName("有 instanceId：确定性 id，canvasSessionId 存实例键")
    void multiInstanceAppSession_usesDeterministicId() {
        String appId = "world";
        String instanceId = "world-session-uuid-123";
        UUID key = UUID.nameUUIDFromBytes((appId + "|" + instanceId).getBytes(StandardCharsets.UTF_8));
        String expectedId = "app-" + appId + "-" + key;

        when(repo.findById(expectedId)).thenReturn(Optional.empty());
        when(repo.save(any(UiSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UiSession ui = store.ensureApp(appId, "HR", instanceId);

        assertThat(ui.id()).isEqualTo(expectedId);
        assertThat(ui.agentSessionId()).isEqualTo(expectedId);
        verify(repo).save(argThat(e ->
                expectedId.equals(e.getId())
                        && instanceId.equals(e.getCanvasSessionId())));
    }

    @Test
    @DisplayName("已存在记录：更新 name 与 canvasSessionId，不新建 id")
    void ensureApp_updatesExisting() {
        String appId = "world";
        String instanceId = "same";
        UUID key = UUID.nameUUIDFromBytes((appId + "|" + instanceId).getBytes(StandardCharsets.UTF_8));
        String id = "app-" + appId + "-" + key;

        UiSessionEntity existing = new UiSessionEntity(id, "app", "Old", null, id, Instant.now());
        existing.setCanvasSessionId("stale");

        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(any(UiSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UiSession ui = store.ensureApp(appId, "New Title", instanceId);

        assertThat(ui.id()).isEqualTo(id);
        verify(repo).save(argThat(e ->
                "New Title".equals(e.getName()) && instanceId.equals(e.getCanvasSessionId())));
    }
}
