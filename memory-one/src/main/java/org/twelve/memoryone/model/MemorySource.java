package org.twelve.memoryone.model;

/**
 * Memory 来源，影响初始置信度（confidence）的默认值。
 */
public enum MemorySource {
    USER_STATED,
    INFERRED,
    SYSTEM;

    public float defaultConfidence() {
        return switch (this) {
            case USER_STATED -> 0.95f;
            case INFERRED    -> 0.65f;
            case SYSTEM      -> 0.90f;
        };
    }
}
