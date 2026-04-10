package org.twelve.memoryone.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.twelve.memoryone.db.MemoryEntity;
import org.twelve.memoryone.db.MemoryRepository;
import org.twelve.memoryone.model.*;
import org.twelve.memoryone.query.MemoryQuery;

import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL（Spring Data JPA）实现的 {@link MemoryStore}。
 */
@Component
public class JdbcMemoryStore implements MemoryStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MemoryRepository repo;

    @Override
    @Transactional
    public Memory save(Memory memory) {
        repo.save(toEntity(memory));
        return memory;
    }

    @Override
    @Transactional
    public Memory supersede(UUID oldId, Memory newer) {
        Memory saved = save(newer);
        repo.markSuperseded(oldId.toString(), newer.id().toString(), Instant.now());
        return saved;
    }

    @Override
    @Transactional
    public Memory promote(UUID id, MemoryScope newScope) {
        Optional<MemoryEntity> opt = repo.findById(id.toString());
        if (opt.isEmpty()) throw new IllegalArgumentException("Memory not found: " + id);
        Memory newMem = fromEntity(opt.get()).withScope(newScope);
        return supersede(id, newMem);
    }

    @Override
    @Transactional
    public void addLink(UUID fromId, MemoryLink link) {
        repo.findById(fromId.toString()).ifPresent(e -> {
            try {
                List<Map<String, Object>> links = parseJsonList(e.getLinkedTo());
                Map<String, Object> linkMap = new LinkedHashMap<>();
                linkMap.put("target_id", link.targetId().toString());
                linkMap.put("link_type", link.linkType().name());
                linkMap.put("weight", link.weight());
                links.add(linkMap);
                e.setLinkedTo(JSON.writeValueAsString(links));
                e.setUpdatedAt(Instant.now());
                repo.save(e);
            } catch (Exception ignored) {}
        });
    }

    @Override
    @Transactional
    public void markContradiction(UUID id1, UUID id2) {
        addToUuidList(id1.toString(), id2.toString());
        addToUuidList(id2.toString(), id1.toString());
    }

    @Override
    public Optional<Memory> findById(UUID id) {
        return repo.findById(id.toString()).map(this::fromEntity);
    }

    @Override
    public List<Memory> query(MemoryQuery query) {
        Instant now   = Instant.now();
        String scope  = query.scopes().size() == 1 ? query.scopes().iterator().next().name() : null;
        String type   = query.types().size()  == 1 ? query.types().iterator().next().name()  : null;
        String userId = query.userId() != null ? query.userId() : "default";

        List<MemoryEntity> entities;
        if (query.textSearch() != null && !query.textSearch().isBlank()) {
            entities = repo.searchByKeyword(query.agentId(), userId, query.textSearch(), now);
        } else {
            entities = repo.findActive(
                    query.agentId(), userId, scope, type,
                    query.sessionId(), query.workspaceId(), query.minImportance(), now);
        }

        return entities.stream()
                .limit(query.limit())
                .map(this::fromEntity)
                .sorted(Comparator.comparingDouble(
                        (Memory m) -> m.retrievalScore(0.5)).reversed())
                .toList();
    }

    @Override
    @Transactional
    public void recordAccess(List<UUID> ids) {
        if (ids.isEmpty()) return;
        repo.recordAccess(ids.stream().map(UUID::toString).toList(), Instant.now());
    }

    @Override
    public List<Memory> findMemoryInstructions(String agentId, String userId, String sessionId) {
        return repo.findMemoryInstructions(
                        agentId, userId != null ? userId : "default", sessionId, Instant.now())
                .stream().map(this::fromEntity).toList();
    }

    // ── Entity ↔ Model ────────────────────────────────────────────────────

    private MemoryEntity toEntity(Memory m) {
        MemoryEntity e = new MemoryEntity();
        e.setId(m.id().toString());
        e.setType(m.type().name());
        e.setScope(m.scope().name());
        e.setAgentId(m.agentId());
        e.setUserId(m.userId() != null ? m.userId() : "default");
        e.setWorkspaceId(m.workspaceId());
        e.setSessionId(m.sessionId());
        e.setContent(m.content());
        e.setStructured(m.structured());
        e.setTags(tagsToJson(m.tags()));
        e.setImportance(m.importance());
        e.setConfidence(m.confidence());
        e.setSource(m.source().name());
        e.setHorizon(m.horizon().name());
        Instant now = Instant.now();
        e.setCreatedAt(m.createdAt() != null ? m.createdAt() : now);
        e.setUpdatedAt(m.updatedAt() != null ? m.updatedAt() : now);
        e.setLastAccessed(m.lastAccessed() != null ? m.lastAccessed() : now);
        e.setAccessCount(m.accessCount());
        e.setExpiresAt(m.expiresAt());
        e.setSupersededBy(m.supersededBy() != null ? m.supersededBy().toString() : null);
        e.setSubjectEntity(m.subjectEntity());
        e.setPredicate(m.predicate());
        e.setObjectEntity(m.objectEntity());
        e.setContradicts(uuidsToJson(m.contradicts()));
        e.setLinkedTo(linksToJson(m.linkedTo()));
        e.setProvenance(uuidsToJson(m.provenance()));
        return e;
    }

    private Memory fromEntity(MemoryEntity e) {
        return new Memory(
            UUID.fromString(e.getId()),
            MemoryType.valueOf(e.getType()),
            MemoryScope.valueOf(e.getScope()),
            e.getAgentId(),
            e.getUserId() != null ? e.getUserId() : "default",
            e.getWorkspaceId(),
            e.getSessionId(),
            e.getContent(),
            e.getStructured(),
            parseTags(e.getTags()),
            e.getImportance(),
            e.getConfidence(),
            MemorySource.valueOf(e.getSource()),
            MemoryHorizon.valueOf(e.getHorizon()),
            e.getSubjectEntity(),
            e.getPredicate(),
            e.getObjectEntity(),
            e.getCreatedAt(),
            e.getUpdatedAt(),
            e.getLastAccessed(),
            e.getAccessCount(),
            e.getExpiresAt(),
            e.getSupersededBy() != null ? UUID.fromString(e.getSupersededBy()) : null,
            parseUuids(e.getContradicts()),
            parseLinks(e.getLinkedTo()),
            parseUuids(e.getProvenance())
        );
    }

    private void addToUuidList(String entityId, String targetId) {
        repo.findById(entityId).ifPresent(e -> {
            try {
                List<String> ids = parseJsonStringList(e.getContradicts());
                if (!ids.contains(targetId)) {
                    ids.add(targetId);
                    e.setContradicts(JSON.writeValueAsString(ids));
                    e.setUpdatedAt(Instant.now());
                    repo.save(e);
                }
            } catch (Exception ignored) {}
        });
    }

    private String tagsToJson(List<String> tags) {
        try { return tags == null ? "[]" : JSON.writeValueAsString(tags); }
        catch (Exception e) { return "[]"; }
    }

    private List<String> parseTags(String json) { return parseJsonStringList(json); }

    private String uuidsToJson(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return "[]";
        try { return JSON.writeValueAsString(ids.stream().map(UUID::toString).toList()); }
        catch (Exception e) { return "[]"; }
    }

    private List<UUID> parseUuids(String json) {
        return parseJsonStringList(json).stream()
                .map(s -> { try { return UUID.fromString(s); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .toList();
    }

    private String linksToJson(List<MemoryLink> links) {
        if (links == null || links.isEmpty()) return "[]";
        try {
            List<Map<String, Object>> list = links.stream().map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("target_id", l.targetId().toString());
                m.put("link_type", l.linkType().name());
                m.put("weight",    l.weight());
                return m;
            }).toList();
            return JSON.writeValueAsString(list);
        } catch (Exception e) { return "[]"; }
    }

    @SuppressWarnings("unchecked")
    private List<MemoryLink> parseLinks(String json) {
        try {
            List<Map<String, Object>> list = JSON.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return list.stream().map(m -> new MemoryLink(
                    UUID.fromString((String) m.get("target_id")),
                    LinkType.valueOf((String) m.get("link_type")),
                    ((Number) m.get("weight")).floatValue()
            )).toList();
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String json) {
        try { return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private List<String> parseJsonStringList(String json) {
        try { return JSON.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }
}
