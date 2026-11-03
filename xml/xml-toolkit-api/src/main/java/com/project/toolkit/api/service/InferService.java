package com.project.toolkit.api.service;

import com.project.toolkit.api.entity.FileGroup;
import com.project.toolkit.api.entity.Job;
import com.project.toolkit.api.repository.JobFileRepository;
import com.project.toolkit.api.security.PathSecurity;
import com.project.toolkit.api.util.FileGrouper;
import com.project.toolkit.infer.InferCommand;
import com.project.toolkit.infer.InferOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Runs the group-then-infer pipeline for one infer job: materialize the uploaded samples, group
 * them by message type (A-side of the API spec), then run the real engine's {@link InferCommand}
 * once per group so groups/review/decisions/register all have something to read. Each group's own
 * subset of files is copied into {@code jobDir/groups/<groupKey>/samples} so the engine (which
 * reads a whole directory) only ever sees that one message type.
 */
@Service
public final class InferService {

    public record GroupInferenceResult(FileGroup group, Path xsdPath, Path reviewJsonPath, Path reviewMdPath,
                                        int validSamples, int totalSamples) {
    }

    private final JobFileRepository jobFileRepository;
    private final PathSecurity pathSecurity;
    private final JobService jobService;

    public InferService(JobFileRepository jobFileRepository, PathSecurity pathSecurity, JobService jobService) {
        this.jobFileRepository = jobFileRepository;
        this.pathSecurity = pathSecurity;
        this.jobService = jobService;
    }

    /** Reads the uploaded zip/files into a short-lived staging directory, synchronously on the
     * request thread -- before the request completes and the servlet container is free to clean
     * up its multipart temp files out from under an async job that hasn't read them yet. Returns
     * null when the input came from {@code serverPath} instead, which reads straight off disk
     * later and has no such race. */
    public Path stageUpload(MultipartFile zip, MultipartFile[] files, String serverPath) throws IOException {
        int provided = (zip != null && !zip.isEmpty() ? 1 : 0) + (files != null && files.length > 0 ? 1 : 0)
                + (serverPath != null && !serverPath.isBlank() ? 1 : 0);
        if (provided != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide exactly one of: zip, files, serverPath");
        }
        if (serverPath != null && !serverPath.isBlank()) {
            return null;
        }
        Path staging = Files.createTempDirectory("infer-upload-");
        if (zip != null && !zip.isEmpty()) {
            pathSecurity.extractZipSafely(zip.getInputStream(), staging);
        } else {
            for (MultipartFile f : files) {
                Files.copy(f.getInputStream(), staging.resolve(Path.of(f.getOriginalFilename()).getFileName()));
            }
        }
        return staging;
    }

    /** The full orchestration behind {@code POST /api/infer}'s job: move the already-staged input
     * (or read it from {@code serverPath}) into place, group, infer each group, and record what
     * the job controller/other endpoints need in {@code job.attributes}. */
    public Object runInfer(Job job, Path stagedUpload, String serverPath,
                            Path baselineXsd, Integer maxSamples) throws IOException {
        Path jobDir = jobFileRepository.dirFor(job.id);
        Path samplesDir = jobDir.resolve("samples");
        Files.createDirectories(samplesDir);
        if (stagedUpload != null) {
            moveStagedInto(stagedUpload, samplesDir);
        } else {
            copyFromServerPath(serverPath, samplesDir);
        }

        FileGrouper.Result grouped = groupSamples(samplesDir);
        job.attributes.put("groups", grouped.groups());
        job.attributes.put("brokenFiles", grouped.brokenFiles());

        List<GroupInferenceResult> results = new ArrayList<>();
        int done = 0;
        for (FileGroup group : grouped.groups()) {
            if (job.cancelRequested.get()) {
                break;
            }
            results.add(inferGroup(jobDir, samplesDir, group, maxSamples, baselineXsd));
            jobService.updateProgress(job, Math.min(99, (int) (100.0 * ++done / Math.max(1, grouped.groups().size()))));
        }
        job.attributes.put("groupResults", results);

        if (results.size() == 1) {
            job.attributes.put("downloadPath", results.get(0).xsdPath().toString());
            job.attributes.put("downloadContentType", "application/xml");
            job.attributes.put("downloadFileName", "inferred.xsd");
            job.attributes.put("reportJsonPath", results.get(0).reviewJsonPath().toString());
        }
        return Map.of("groupCount", grouped.groups().size(), "brokenFileCount", grouped.brokenFiles().size());
    }

    private void moveStagedInto(Path staging, Path samplesDir) throws IOException {
        try (var list = Files.list(staging)) {
            for (Path p : list.toList()) {
                Files.move(p, samplesDir.resolve(p.getFileName()));
            }
        }
        Files.delete(staging);
    }

    private void copyFromServerPath(String serverPath, Path samplesDir) throws IOException {
        Path source = pathSecurity.resolve(serverPath);
        if (Files.isDirectory(source)) {
            try (var list = Files.list(source)) {
                for (Path p : list.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    Files.copy(p, samplesDir.resolve(p.getFileName()));
                }
            }
        } else {
            Files.copy(source, samplesDir.resolve(source.getFileName()));
        }
    }

    public FileGrouper.Result groupSamples(Path samplesDir) throws IOException {
        return FileGrouper.group(samplesDir);
    }

    public GroupInferenceResult inferGroup(Path jobDir, Path samplesDir, FileGroup group, Integer maxSamples,
                                            Path baselineXsd) throws IOException {
        Path groupDir = jobDir.resolve("groups").resolve(safeDirName(group.groupKey()));
        Path groupSamplesDir = groupDir.resolve("samples");
        Files.createDirectories(groupSamplesDir);

        List<String> fileNames = group.fileNames();
        if (maxSamples != null && maxSamples > 0 && fileNames.size() > maxSamples) {
            fileNames = fileNames.subList(0, maxSamples);
        }
        for (String fileName : fileNames) {
            Files.copy(samplesDir.resolve(fileName), groupSamplesDir.resolve(fileName));
        }

        InferOptions opts = new InferOptions();
        opts.xmlInput = groupSamplesDir;
        opts.outXsd = groupDir.resolve("inferred.xsd");
        opts.baselineXsd = baselineXsd;
        InferCommand.run(opts);

        Path reviewJson = groupDir.resolve("inferred_review.json");
        Path reviewMd = groupDir.resolve("inferred_review.md");
        int[] counts = revalidateGroup(opts.outXsd, groupSamplesDir);
        return new GroupInferenceResult(group, opts.outXsd, reviewJson, reviewMd, counts[0], counts[1]);
    }

    /** Re-validates a group's samples against its current XSD (used both right after inferring,
     * and again after decisions are recorded, to report a real, current validation count). */
    public int[] revalidateGroup(Path xsd, Path samplesDir) throws IOException {
        int valid = 0, total = 0;
        try (var files = Files.list(samplesDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                total++;
                if (com.project.toolkit.validate.XsdValidatorCache.validate(xsd, f).valid()) {
                    valid++;
                }
            }
        }
        return new int[]{valid, total};
    }

    private static String safeDirName(String groupKey) {
        return groupKey.replaceAll("[^a-zA-Z0-9._#,-]", "_");
    }
}
