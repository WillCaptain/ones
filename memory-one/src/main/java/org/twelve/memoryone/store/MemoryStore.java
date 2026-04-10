package org.twelve.memoryone.store;

import org.twelve.memoryone.model.Memory;
import org.twelve.memoryone.model.MemoryLink;
import org.twelve.memoryone.model.MemoryScope;
import org.twelve.memoryone.query.MemoryQuery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Memory 持久化接口。
 *
 * <p>所有"更新"操作通过 {@link #supersede(UUID, Memory)} 实现：
 * 旧记录标记 superseded_by，新记录插入——保留完整历史。
 */
public interface MemoryStore {

    Memory save(Memory memory);

    Memory supersede(UUID oldId, Memory newer);

    Memory promote(UUID id, MemoryScope newScope);

    void addLink(UUID fromId, MemoryLink link);

    void markContradiction(UUID id1, UUID id2);

    Optional<Memory> findById(UUID id);

    List<Memory> query(MemoryQuery query);

    void recordAccess(List<UUID> ids);

    List<Memory> findMemoryInstructions(String agentId, String userId, String sessionId);
}
