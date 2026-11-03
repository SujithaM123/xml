package com.project.toolkit.api.controller;

import com.project.toolkit.api.dto.InferDecisionsRequest;
import com.project.toolkit.api.entity.Job;
import com.project.toolkit.api.entity.SchemaRecord;
import com.project.toolkit.api.repository.DecisionRepository;
import com.project.toolkit.api.service.InferService;
import com.project.toolkit.api.service.JobService;
import com.project.toolkit.api.service.SchemaService;
import com.project.toolkit.api.util.ReviewJsonFilter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/infer")
public class InferController {

    private final JobService jobService;
    private final InferService inferService;
    private final DecisionRepository decisionRepository;
    private final SchemaService schemaService;

    public InferController(JobService jobService, InferService inferService,
                            DecisionRepository decisionRepository, SchemaService schemaService) {
        this.jobService = jobService;
        this.inferService = inferService;
        this.decisionRepository = decisionRepository;
        this.schemaService = schemaService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> infer(@RequestParam(required = false) MultipartFile zip,
                                                       @RequestParam(required = false) MultipartFile[] files,
                                                       @RequestParam(required = false) String serverPath,
                                                       @RequestParam(required = false) String baselineSchemaId,
                                                       @RequestParam(required = false) Integer maxSamples) throws IOException {
        Path baselineXsd = baselineSchemaId == null ? null
                : schemaService.xsdPath(schemaService.get(baselineSchemaId).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such baseline schema: " + baselineSchemaId)));

        // Staged synchronously, on this request thread: the servlet container is free to clean up
        // multipart temp files as soon as the request completes, which would otherwise race an
        // async job that hasn't read them yet.
        Path staged = inferService.stageUpload(zip, files, serverPath);
        Job job = jobService.submit("infer", j -> inferService.runInfer(j, staged, serverPath, baselineXsd, maxSamples));
        return ResponseEntity.accepted().body(Map.of("jobId", job.id));
    }

    @GetMapping("/{id}/groups")
    public Map<String, Object> groups(@PathVariable String id) {
        Job job = find(id);
        return Map.of(
                "groups", job.attributes.getOrDefault("groups", List.of()),
                "brokenFiles", job.attributes.getOrDefault("brokenFiles", List.of()),
                "unknownFiles", List.of()
        );
    }

    @GetMapping("/{id}/review")
    public ResponseEntity<String> review(@PathVariable String id, @RequestParam(required = false) String group) throws IOException {
        Job job = find(id);
        InferService.GroupInferenceResult result = groupResult(job, group);
        String groupKey = result.group().groupKey();
        Map<String, String> existingDecisions = decisionRepository.decisionsFor(groupKey);

        String json = Files.readString(result.reviewJsonPath());
        // Field paths with a recorded decision aren't asked about again (the API spec's "apply
        // automatically next time" rule).
        if (!existingDecisions.isEmpty()) {
            json = ReviewJsonFilter.filterOutDecidedPaths(json, existingDecisions);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    /** Per-group XSD download -- the generic GET /api/jobs/{id}/download only works when a job
     * produced exactly one group's result, so a multi-group infer job needs this instead. */
    @GetMapping("/{id}/xsd")
    public ResponseEntity<FileSystemResource> xsd(@PathVariable String id, @RequestParam(required = false) String group) {
        Job job = find(id);
        InferService.GroupInferenceResult result = groupResult(job, group);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("inferred.xsd").build().toString())
                .body(new FileSystemResource(result.xsdPath()));
    }

    @PostMapping("/{id}/decisions")
    public Map<String, Object> decisions(@PathVariable String id, @RequestBody InferDecisionsRequest req) throws IOException {
        Job job = find(id);
        InferService.GroupInferenceResult result = groupResult(job, req.group());
        String groupKey = result.group().groupKey();
        decisionRepository.record(groupKey, req.decisions());

        // Re-validate the group's samples against its current XSD. The engine doesn't yet
        // restructure the XSD from individual field decisions (recorded above for reuse, per
        // the spec's "remember... apply automatically next time" -- but this build only avoids
        // re-asking about them, it doesn't yet rewrite the schema from them); this reports the
        // real, current validation count rather than a number implying more happened.
        int[] counts = inferService.revalidateGroup(result.xsdPath(), result.xsdPath().getParent().resolve("samples"));
        return Map.of("group", groupKey, "validSamples", counts[0], "totalSamples", counts[1],
                "note", "Decisions are recorded and won't be re-asked about; only enumeration/datatype "
                        + "decisions that change the XSD text are reflected in validSamples/totalSamples above.");
    }

    @PostMapping("/{id}/register")
    public ResponseEntity<SchemaRecord> register(@PathVariable String id,
                                                  @RequestParam(required = false) String group,
                                                  @RequestParam(required = false) String name) throws IOException {
        Job job = find(id);
        InferService.GroupInferenceResult result = groupResult(job, group);
        String effectiveName = name != null ? name : result.group().rootElementName();
        try (var in = Files.newInputStream(result.xsdPath())) {
            SchemaRecord record = schemaService.register(effectiveName, in, null);
            return ResponseEntity.status(HttpStatus.CREATED).body(record);
        }
    }

    @SuppressWarnings("unchecked")
    private InferService.GroupInferenceResult groupResult(Job job, String groupKey) {
        List<InferService.GroupInferenceResult> results =
                (List<InferService.GroupInferenceResult>) job.attributes.get("groupResults");
        if (results == null || results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job has no inferred groups yet");
        }
        if (groupKey == null) {
            return results.get(0);
        }
        return results.stream().filter(r -> r.group().groupKey().equals(groupKey)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group: " + groupKey));
    }

    private Job find(String id) {
        return jobService.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job: " + id));
    }
}
