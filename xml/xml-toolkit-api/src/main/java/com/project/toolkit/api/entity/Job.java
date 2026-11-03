package com.project.toolkit.api.entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** One background unit of work (a generate or infer run) and its live state. Every long action
 * in the API returns a {@code jobId} referencing one of these (per the API spec). */
public final class Job {

    public final String id;
    public final String type; // "generate" | "infer"
    public final Instant createdAt = Instant.now();
    public final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    public final List<java.util.function.Consumer<Map<String, Object>>> eventListeners = new CopyOnWriteArrayList<>();

    public volatile JobStatus status = JobStatus.PENDING;
    public volatile int progressPercent = 0;
    public volatile String errorMessage;
    public volatile Object result;
    /** Extra fields a specific job type wants to expose via GET /api/jobs/{id} and SSE events
     * (e.g. infer's groups once computed) without every job type needing its own DTO. */
    public final Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();

    public Job(String id, String type) {
        this.id = id;
        this.type = type;
    }

    /** Public (not package-private as originally, since the entity/service package split means
     * JobService -- the only caller -- now lives outside this package). */
    public void emit(Map<String, Object> event) {
        for (var listener : eventListeners) {
            listener.accept(event);
        }
    }
}
