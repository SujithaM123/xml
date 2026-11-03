package com.project.toolkit.api.service;

import com.project.toolkit.api.dto.GenerateRequest;
import com.project.toolkit.api.entity.Job;
import com.project.toolkit.api.entity.SchemaRecord;
import com.project.toolkit.api.repository.JobFileRepository;
import com.project.toolkit.api.security.PathSecurity;
import com.project.toolkit.generate.ContentLevel;
import com.project.toolkit.generate.GenerateCommand;
import com.project.toolkit.generate.GenerateOptions;
import com.project.toolkit.generate.GenerateResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

/** The full orchestration behind {@code POST /api/generate}'s job: build the engine's
 * {@link GenerateOptions} from the request, wire up cancellation/progress against the job, run
 * {@link GenerateCommand}, and record what the job controller needs in {@code job.attributes}. */
@Service
public final class GenerateService {

    private final SchemaService schemaService;
    private final JobFileRepository jobFileRepository;
    private final PathSecurity pathSecurity;
    private final JobService jobService;

    public GenerateService(SchemaService schemaService, JobFileRepository jobFileRepository,
                            PathSecurity pathSecurity, JobService jobService) {
        this.schemaService = schemaService;
        this.jobFileRepository = jobFileRepository;
        this.pathSecurity = pathSecurity;
        this.jobService = jobService;
    }

    public GenerateResult runGenerate(Job job, SchemaRecord schema, GenerateRequest req) throws Exception {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = schemaService.xsdPath(schema);
        opts.count = req.count() != null ? req.count() : 1000;
        if (req.level() != null) {
            ContentLevel.fromNumber(req.level()); // validate eagerly with the same error message as the CLI
            opts.level = req.level();
        }
        opts.size = req.size() != null ? req.size() : "mixed";
        opts.errorPercent = req.errorPercent() != null ? req.errorPercent() : 0;
        opts.errorTypes = req.errorTypes();
        opts.seed = req.seed() != null ? req.seed() : System.nanoTime();
        opts.threads = req.threads() != null ? req.threads() : Runtime.getRuntime().availableProcessors();
        opts.output = "zip";
        if (req.profilePath() != null) {
            opts.profile = pathSecurity.resolve(req.profilePath());
        }

        Path jobDir = jobFileRepository.dirFor(job.id);
        Path zipPath = jobDir.resolve("output.zip");
        opts.out = zipPath;

        if (Boolean.TRUE.equals(req.includeSupplement())) {
            if (schema.linkedSupplementId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "includeSupplement was requested but schema " + schema.id() + " has no linkedSupplementId");
            }
            SchemaRecord supplement = schemaService.get(schema.linkedSupplementId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Linked supplement schema not found: " + schema.linkedSupplementId()));
            opts.supplement = schemaService.xsdPath(supplement);
        }

        opts.cancelled = () -> job.cancelRequested.get();
        opts.onFileDone = done -> jobService.updateProgress(job, Math.min(99, (int) (100.0 * done / opts.count)));

        GenerateResult result = GenerateCommand.run(opts);
        job.attributes.put("downloadPath", zipPath.toString());
        job.attributes.put("downloadContentType", "application/zip");
        job.attributes.put("downloadFileName", "generated.zip");
        // The zip (opts.output = "zip") only ever contains generated XMLs -- GenerateCommand
        // writes report.json/csv and manifest.json/csv as loose files beside it in this same
        // jobDir instead, exactly like an infer job's own report files, so GET
        // /api/jobs/{id}/report can read them the same way for both job types.
        job.attributes.put("reportJsonPath", jobDir.resolve("report.json").toString());
        job.attributes.put("reportCsvPath", jobDir.resolve("report.csv").toString());
        return result;
    }
}
