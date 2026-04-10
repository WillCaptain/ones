package org.twelve.memoryone.query;

import org.twelve.memoryone.model.MemoryScope;
import org.twelve.memoryone.model.MemoryType;

import java.util.EnumSet;
import java.util.Set;

/**
 * Memory 查询参数（Builder 模式）。
 */
public final class MemoryQuery {

    private final String agentId;
    private final String userId;
    private final String sessionId;
    private final String workspaceId;
    private final Set<MemoryType>  types;
    private final Set<MemoryScope> scopes;
    private final float minImportance;
    private final int   limit;
    private final String textSearch;
    private final float[] embedding;

    private MemoryQuery(Builder b) {
        this.agentId       = b.agentId;
        this.userId        = b.userId;
        this.sessionId     = b.sessionId;
        this.workspaceId   = b.workspaceId;
        this.types         = b.types.isEmpty() ? EnumSet.allOf(MemoryType.class) : Set.copyOf(b.types);
        this.scopes        = b.scopes.isEmpty() ? EnumSet.allOf(MemoryScope.class) : Set.copyOf(b.scopes);
        this.minImportance = b.minImportance;
        this.limit         = b.limit;
        this.textSearch    = b.textSearch;
        this.embedding     = b.embedding;
    }

    public String agentId()          { return agentId; }
    public String userId()           { return userId; }
    public String sessionId()        { return sessionId; }
    public String workspaceId()      { return workspaceId; }
    public Set<MemoryType> types()   { return types; }
    public Set<MemoryScope> scopes() { return scopes; }
    public float minImportance()     { return minImportance; }
    public int limit()               { return limit; }
    public String textSearch()       { return textSearch; }
    public float[] embedding()       { return embedding; }

    public static Builder forAgent(String agentId) {
        return new Builder(agentId);
    }

    public static final class Builder {
        private final String agentId;
        private String userId = "default";
        private String sessionId;
        private String workspaceId;
        private final Set<MemoryType>  types  = EnumSet.noneOf(MemoryType.class);
        private final Set<MemoryScope> scopes = EnumSet.noneOf(MemoryScope.class);
        private float  minImportance = 0f;
        private int    limit         = 20;
        private String textSearch;
        private float[] embedding;

        private Builder(String agentId) { this.agentId = agentId; }

        public Builder session(String sessionId)      { this.sessionId = sessionId; return this; }
        public Builder workspace(String workspaceId)  { this.workspaceId = workspaceId; return this; }
        public Builder userId(String userId)          { this.userId = userId; return this; }
        public Builder types(MemoryType... types)     { this.types.addAll(Set.of(types)); return this; }
        public Builder scopes(MemoryScope... scopes)  { this.scopes.addAll(Set.of(scopes)); return this; }
        public Builder minImportance(float v)         { this.minImportance = v; return this; }
        public Builder limit(int n)                   { this.limit = n; return this; }
        public Builder textSearch(String kw)          { this.textSearch = kw; return this; }
        public Builder embedding(float[] vec)         { this.embedding = vec; return this; }

        public MemoryQuery build() { return new MemoryQuery(this); }
    }
}
