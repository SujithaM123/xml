package com.project.toolkit.api.repository;

import com.project.toolkit.api.config.AppProperties;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where a job's output lives on disk. */
@Repository
public final class JobFileRepository {

    private final Path jobsRoot;

    public JobFileRepository(AppProperties properties) throws IOException {
        this.jobsRoot = Path.of(properties.getDataDir()).resolve("jobs");
        Files.createDirectories(jobsRoot);
    }

    public Path dirFor(String jobId) throws IOException {
        Path dir = jobsRoot.resolve(jobId);
        Files.createDirectories(dir);
        return dir;
    }
}
