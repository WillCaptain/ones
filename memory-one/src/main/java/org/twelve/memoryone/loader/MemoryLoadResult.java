package org.twelve.memoryone.loader;

import java.util.List;
import java.util.UUID;

/**
 * Memory 加载结果：注入文本 + 已加载的 memory ID 列表。
 */
public record MemoryLoadResult(
    String     injectionText,
    List<UUID> loadedIds
) {
    public static final MemoryLoadResult EMPTY = new MemoryLoadResult("", List.of());

    public boolean isEmpty() {
        return injectionText == null || injectionText.isBlank();
    }
}
