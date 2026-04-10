package org.twelve.memoryone.model;

import java.util.UUID;

/**
 * 两条 Memory 之间的有向关联。
 */
public record MemoryLink(UUID targetId, LinkType linkType, float weight) {

    public MemoryLink {
        if (weight < 0 || weight > 1)
            throw new IllegalArgumentException("weight must be in [0, 1]: " + weight);
    }

    public static MemoryLink of(UUID targetId, LinkType linkType) {
        return new MemoryLink(targetId, linkType, 0.8f);
    }
}
