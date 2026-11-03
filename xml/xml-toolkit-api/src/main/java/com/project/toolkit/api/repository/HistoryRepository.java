package com.project.toolkit.api.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.toolkit.api.config.AppProperties;
import com.project.toolkit.api.entity.HistoryEntry;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pure file-based persistence for history entries: the in-memory list and the history.json
 * backing file. Entry construction (id/timestamp assignment) is
 * {@link com.project.toolkit.api.service.HistoryService}'s job, not this class's. */
@Repository
public final class HistoryRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final List<HistoryEntry> entries = new CopyOnWriteArrayList<>();
    private final Path file;

    public HistoryRepository(AppProperties properties) throws IOException {
        this.file = Path.of(properties.getDataDir()).resolve("history.json");
        load();
    }

    public synchronized void save(HistoryEntry entry) throws IOException {
        entries.add(0, entry); // most recent first
        persist();
    }

    public List<HistoryEntry> findAll() {
        return List.copyOf(entries);
    }

    public Optional<HistoryEntry> findById(String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        HistoryEntry[] loaded = MAPPER.readValue(file.toFile(), HistoryEntry[].class);
        entries.addAll(List.of(loaded));
    }

    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new ArrayList<>(entries));
    }
}
