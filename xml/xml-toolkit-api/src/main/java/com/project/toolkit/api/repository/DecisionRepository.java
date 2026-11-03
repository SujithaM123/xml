package com.project.toolkit.api.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.toolkit.api.config.AppProperties;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers a human's decision on one reviewed field (accept/reject a suggestion, override a
 * value) keyed by (group key, field path), persisted across restarts, so the next inference run
 * on the same message type doesn't ask about the same field again (the API spec's closing rule).
 */
@Repository
public final class DecisionRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** groupKey -> (fieldPath -> decision) */
    private final Map<String, Map<String, String>> decisions = new ConcurrentHashMap<>();
    private final Path file;

    public DecisionRepository(AppProperties properties) throws IOException {
        this.file = Path.of(properties.getDataDir()).resolve("decisions.json");
        load();
    }

    public synchronized void record(String groupKey, Map<String, String> fieldDecisions) throws IOException {
        decisions.computeIfAbsent(groupKey, k -> new ConcurrentHashMap<>()).putAll(fieldDecisions);
        save();
    }

    public Map<String, String> decisionsFor(String groupKey) {
        return decisions.getOrDefault(groupKey, Map.of());
    }

    public boolean hasDecision(String groupKey, String fieldPath) {
        return decisionsFor(groupKey).containsKey(fieldPath);
    }

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Map<String, Map<String, String>> loaded = MAPPER.readValue(file.toFile(), Map.class);
        decisions.putAll(loaded);
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), decisions);
    }
}
