package com.project.toolkit.api.controller;

import com.project.toolkit.api.dto.GenerateRequest;
import com.project.toolkit.api.entity.Job;
import com.project.toolkit.api.entity.SchemaRecord;
import com.project.toolkit.api.service.GenerateService;
import com.project.toolkit.api.service.HistoryService;
import com.project.toolkit.api.service.JobService;
import com.project.toolkit.api.service.SchemaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/generate")
public class GenerateController {

    private final SchemaService schemaService;
    private final JobService jobService;
    private final HistoryService historyService;
    private final GenerateService generateService;

    public GenerateController(SchemaService schemaService, JobService jobService,
                               HistoryService historyService, GenerateService generateService) {
        this.schemaService = schemaService;
        this.jobService = jobService;
        this.historyService = historyService;
        this.generateService = generateService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> generate(@RequestBody GenerateRequest req) throws java.io.IOException {
        if (req.schemaId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schemaId is required");
        }
        SchemaRecord schema = schemaService.get(req.schemaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such schema: " + req.schemaId()));

        Job job = jobService.submit("generate", j -> generateService.runGenerate(j, schema, req));
        historyService.record("generate", job.id, req);
        return ResponseEntity.accepted().body(Map.of("jobId", job.id));
    }
}
