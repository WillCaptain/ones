package org.twelve.entitir.worldone;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.twelve.entitir.worldone.db.UiSessionEntity;
import org.twelve.entitir.worldone.db.UiSessionRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * World One 前端 session 元数据管理，持久化到 PostgreSQL。
 *
 * <p>主 session（id="main"）在初始化时自动确保存在，不可删除。
 */
@Component
public class UiSessionStore {

    @Autowired
    private UiSessionRepository repo;

    @PostConstruct
    @Transactional
    void init() {
        if (!repo.existsById("main")) {
            repo.save(new UiSessionEntity(
                    "main", "conversation", "World One",
                    null, "main", Instant.now()));
        }
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /** 返回活跃 session（未归档）。 */
    public List<UiSession> listActive() {
        return repo.findAllByOrderByCreatedAtAsc().stream()
                .filter(e -> !isArchived(e))
                .map(this::toRecord)
                .toList();
    }

    /** 返回全部 session（含归档）。 */
    public List<UiSession> listAll() {
        return repo.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toRecord)
                .toList();
    }

    public UiSession find(String id) {
        return repo.findById(id).map(this::toRecord).orElse(null);
    }

    /** 按 canvasSessionId（app 侧设计会话 ID）查找活跃 UiSession，用于"进入已有世界"的幂等判断。 */
    public UiSession findByCanvasSessionId(String canvasSessionId) {
        if (canvasSessionId == null || canvasSessionId.isBlank()) return null;
        return repo.findAllByOrderByCreatedAtAsc().stream()
                .filter(e -> canvasSessionId.equals(e.getCanvasSessionId()) && !isArchived(e))
                .map(this::toRecord)
                .findFirst()
                .orElse(null);
    }

    // ── 创建 ────────────────────────────────────────────────────────────────

    @Transactional
    public UiSession create(String type, String name, String agentSessionId) {
        String id = UUID.randomUUID().toString();
        UiSessionEntity e = new UiSessionEntity(
                id, type, name, "active", agentSessionId, Instant.now());
        repo.save(e);
        return toRecord(e);
    }

    // ── Canvas 模式持久化 ─────────────────────────────────────────────────────

    /**
     * 更新 session 的 canvas 状态（进入/退出 Canvas 模式时调用）。
     *
     * @param id              UiSession id
     * @param widgetType      widget 类型（如 "entity-graph"），null 表示退出 Canvas 模式
     * @param canvasSessionId app-side 设计会话 ID（world-entitir 的 WorldOneSession.id），可为 null
     */
    @Transactional
    public void updateCanvas(String id, String widgetType, String canvasSessionId) {
        repo.findById(id).ifPresent(e -> {
            e.setWidgetType(widgetType);
            e.setCanvasSessionId(canvasSessionId);
            repo.save(e);
        });
    }

    // ── 状态变更 ─────────────────────────────────────────────────────────────

    @Transactional
    public boolean complete(String id) {
        return updateStatus(id, "completed");
    }

    @Transactional
    public boolean voidSession(String id) {
        return updateStatus(id, "voided");
    }

    /** 归档 session（软删除）— 主 session 不可归档。 */
    @Transactional
    public boolean archive(String id) {
        return updateStatus(id, "archived");
    }

    /** 恢复已归档的 session 为活跃状态。 */
    @Transactional
    public boolean restore(String id) {
        if ("main".equals(id)) return false;
        return repo.findById(id).map(e -> {
            if (!isArchived(e)) return false; // only restore archived sessions
            e.setStatus("active");
            repo.save(e);
            return true;
        }).orElse(false);
    }

    /** 主 session 不可删除，其余任意 session 均可硬删除。 */
    @Transactional
    public boolean delete(String id) {
        if ("main".equals(id)) return false;
        return repo.findById(id).map(e -> {
            repo.delete(e);
            return true;
        }).orElse(false);
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private boolean updateStatus(String id, String newStatus) {
        if ("main".equals(id)) return false;
        return repo.findById(id).map(e -> {
            e.setStatus(newStatus);
            repo.save(e);
            return true;
        }).orElse(false);
    }

    private boolean isArchived(UiSessionEntity e) {
        String s = e.getStatus();
        return "completed".equals(s) || "voided".equals(s) || "archived".equals(s);
    }

    private UiSession toRecord(UiSessionEntity e) {
        return new UiSession(
                e.getId(), e.getType(), e.getName(), e.getStatus(),
                e.getAgentSessionId(),
                e.getWidgetType(),
                e.getCanvasSessionId(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : "");
    }
}
