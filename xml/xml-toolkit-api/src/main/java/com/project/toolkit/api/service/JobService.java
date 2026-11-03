package com.project.toolkit.api.service;

import com.project.toolkit.api.entity.Job;
import com.project.toolkit.api.entity.JobStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tracks every background job, runs its work on a shared pool, and fans out progress/completion
 * as both a pollable status (GET /api/jobs/{id}) and Server-Sent Events (GET /api/jobs/{id}/events). */
@Service
public final class JobService {

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** @param work receives the Job so it can check {@code cancelRequested} and update
     *             {@code progressPercent}/{@code attributes} as it runs; its return value
     *             becomes {@code job.result} on success. */
    public Job submit(String type, JobWork work) {
        Job job = new Job(UUID.randomUUID().toString(), type);
        jobs.put(job.id, job);
        executor.submit(() -> {
            job.status = JobStatus.RUNNING;
            job.emit(statusEvent(job));
            try {
                Object result = work.run(job);
                if (job.cancelRequested.get()) {
                    job.status = JobStatus.CANCELLED;
                } else {
                    job.result = result;
                    job.progressPercent = 100;
                    job.status = JobStatus.SUCCEEDED;
                }
            } catch (Exception e) {
                job.status = JobStatus.FAILED;
                job.errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            job.emit(statusEvent(job));
        });
        return job;
    }

    public Optional<Job> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public boolean cancel(String id) {
        Job job = jobs.get(id);
        if (job == null || job.status == JobStatus.SUCCEEDED || job.status == JobStatus.FAILED) {
            return false;
        }
        job.cancelRequested.set(true);
        return true;
    }

    public void updateProgress(Job job, int percent) {
        job.progressPercent = percent;
        job.emit(statusEvent(job));
    }

    public SseEmitter subscribe(String id) {
        Job job = jobs.get(id);
        SseEmitter emitter = new SseEmitter(0L); // no timeout: a generate/infer run can take a while
        if (job == null) {
            emitter.completeWithError(new java.util.NoSuchElementException("No such job: " + id));
            return emitter;
        }
        java.util.function.Consumer<Map<String, Object>> listener = event -> {
            try {
                emitter.send(event);
                String status = String.valueOf(event.get("status"));
                if (JobStatus.SUCCEEDED.name().equals(status) || JobStatus.FAILED.name().equals(status)
                        || JobStatus.CANCELLED.name().equals(status)) {
                    emitter.complete();
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };
        job.eventListeners.add(listener);
        listener.accept(statusEvent(job)); // current state immediately, so a late subscriber isn't stuck waiting
        return emitter;
    }

    private Map<String, Object> statusEvent(Job job) {
        return Map.of(
                "jobId", job.id,
                "type", job.type,
                "status", job.status.name(),
                "progressPercent", job.progressPercent
        );
    }

    @FunctionalInterface
    public interface JobWork {
        Object run(Job job) throws Exception;
    }
}
