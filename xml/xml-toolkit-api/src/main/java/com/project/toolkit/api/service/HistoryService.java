package com.project.toolkit.api.service;

import com.project.toolkit.api.dto.GenerateRequest;
import com.project.toolkit.api.entity.HistoryEntry;
import com.project.toolkit.api.repository.HistoryRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Past generate/infer runs and their settings (GET /api/history): entry construction
 * (id/timestamp assignment), delegating storage to {@link HistoryRepository}. */
@Service
public final class HistoryService {

    private final HistoryRepository repository;

    public HistoryService(HistoryRepository repository) {
        this.repository = repository;
    }

    public HistoryEntry record(String type, String jobId, GenerateRequest request) throws IOException {
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID().toString(), type, jobId, Instant.now(), request);
        repository.save(entry);
        return entry;
    }

    public List<HistoryEntry> list() {
        return repository.findAll();
    }

    public Optional<HistoryEntry> get(String id) {
        return repository.findById(id);
    }
}
