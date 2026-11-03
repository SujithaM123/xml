package com.project.toolkit.api.controller;

import com.project.toolkit.api.entity.Job;
import com.project.toolkit.api.entity.JobStatus;
import com.project.toolkit.api.service.JobService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        Job job = find(id);
        return Map.of(
                "jobId", job.id,
                "type", job.type,
                "status", job.status.name(),
                "progressPercent", job.progressPercent,
                "errorMessage", job.errorMessage == null ? "" : job.errorMessage,
                "createdAt", job.createdAt.toString()
        );
    }

    @GetMapping(value = "/{id}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String id) {
        find(id); // 404 before opening the stream if the job doesn't exist
        return jobService.subscribe(id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        find(id);
        boolean cancelled = jobService.cancel(id);
        return Map.of("jobId", id, "cancelled", cancelled);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) throws IOException {
        Job job = find(id);
        requireFinished(job);
        Path path = Path.of((String) requireAttribute(job, "downloadPath"));
        String contentType = (String) job.attributes.getOrDefault("downloadContentType", "application/octet-stream");
        String fileName = (String) job.attributes.getOrDefault("downloadFileName", path.getFileName().toString());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(new FileSystemResource(path));
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<String> report(@PathVariable String id,
                                          @RequestParam(defaultValue = "json") String format) throws IOException {
        Job job = find(id);
        requireFinished(job);
        if (!"json".equals(format) && !"csv".equals(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be json or csv");
        }

        // Both job types write report.json/csv as a loose file beside their other output rather
        // than bundling it into a downloadable zip (generate's zip is meant to be XML documents
        // only), so both read the same way here.
        String key = "json".equals(format) ? "reportJsonPath" : "reportCsvPath";
        Object pathAttr = job.attributes.get(key);
        if (pathAttr == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + format + " report for this job type");
        }
        String content = Files.readString(Path.of((String) pathAttr));

        MediaType mediaType = "json".equals(format) ? MediaType.APPLICATION_JSON : MediaType.valueOf("text/csv");
        return ResponseEntity.ok().contentType(mediaType).body(content);
    }

    private Job find(String id) {
        return jobService.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job: " + id));
    }

    private void requireFinished(Job job) {
        if (job.status != JobStatus.SUCCEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is " + job.status.name().toLowerCase() + ", not finished successfully yet");
        }
    }

    private Object requireAttribute(Job job, String key) {
        Object value = job.attributes.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job has no " + key);
        }
        return value;
    }
}
