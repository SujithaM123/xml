package com.project.toolkit.api;

import com.project.toolkit.generate.GenerateCommand;
import com.project.toolkit.generate.GenerateOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InferJobFlowTest {

    @TempDir
    static Path dataDir;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
        registry.add("app.allowed-base-folders", () -> dataDir.toString());
    }

    private static final Path SUPL009 = Path.of("..", "xsd", "SR_2025_Corporate_Action_CAPA_Extension_supl_009_001_11.xsd");

    @Test
    void inferJobGroupsReviewsAndRegistersFromAZipUpload(@TempDir Path tempDir) throws Exception {
        Path samplesDir = tempDir.resolve("samples");
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = SUPL009;
        opts.out = samplesDir;
        opts.count = 10;
        opts.level = 2;
        opts.seed = 1;
        GenerateCommand.run(opts);

        Path zipFile = tempDir.resolve("samples.zip");
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile));
             Stream<Path> files = Files.list(samplesDir).filter(p -> p.toString().endsWith(".xml"))) {
            for (Path f : files.toList()) {
                zos.putNextEntry(new ZipEntry(f.getFileName().toString()));
                zos.write(Files.readAllBytes(f));
                zos.closeEntry();
            }
        }

        TestRestTemplate rest = new TestRestTemplate();
        String base = "http://localhost:" + port;

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("zip", new FileSystemResource(zipFile));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> inferResponse = rest.postForEntity(base + "/api/infer", new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.ACCEPTED, inferResponse.getStatusCode());
        String jobId = inferResponse.getBody().split("\"jobId\":\"")[1].split("\"")[0];

        assertEquals("SUCCEEDED", waitForCompletion(rest, base, jobId));

        ResponseEntity<String> groupsResponse = rest.getForEntity(base + "/api/infer/" + jobId + "/groups", String.class);
        assertEquals(HttpStatus.OK, groupsResponse.getStatusCode());
        assertTrue(groupsResponse.getBody().contains("\"fileNames\""), "groups response:\n" + groupsResponse.getBody());
        assertTrue(groupsResponse.getBody().contains("DTCCCAPASD1") || groupsResponse.getBody().contains("rootElementName"),
                "groups response should identify the root element:\n" + groupsResponse.getBody());

        ResponseEntity<String> reviewResponse = rest.getForEntity(base + "/api/infer/" + jobId + "/review", String.class);
        assertEquals(HttpStatus.OK, reviewResponse.getStatusCode());
        assertTrue(reviewResponse.getBody().contains("needsReview"), "review response:\n" + reviewResponse.getBody());

        String decisionsBody = """
                {"decisions":{"Document/DTCCCAPASD1":"accept"}}
                """;
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> decisionsResponse = rest.postForEntity(base + "/api/infer/" + jobId + "/decisions",
                new HttpEntity<>(decisionsBody, jsonHeaders), String.class);
        assertEquals(HttpStatus.OK, decisionsResponse.getStatusCode());
        assertTrue(decisionsResponse.getBody().contains("\"validSamples\""), decisionsResponse.getBody());

        ResponseEntity<String> registerResponse = rest.postForEntity(base + "/api/infer/" + jobId + "/register?name=inferred-supl009",
                null, String.class);
        assertEquals(HttpStatus.CREATED, registerResponse.getStatusCode());
        assertTrue(registerResponse.getBody().contains("\"name\":\"inferred-supl009\""));

        // Decision should now be remembered and filtered out of a fresh review read for the
        // same group (the "apply automatically next time" rule).
        ResponseEntity<String> reviewAfter = rest.getForEntity(base + "/api/infer/" + jobId + "/review", String.class);
        assertFalse(reviewAfter.getBody().contains("\"Document/DTCCCAPASD1\""),
                "a decided-on field should be filtered out of the next review read:\n" + reviewAfter.getBody());
    }

    private String waitForCompletion(TestRestTemplate rest, String base, String jobId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> statusResponse = rest.getForEntity(base + "/api/jobs/" + jobId, String.class);
            String responseBody = statusResponse.getBody();
            if (responseBody.contains("\"SUCCEEDED\"")) return "SUCCEEDED";
            if (responseBody.contains("\"FAILED\"")) return "FAILED: " + responseBody;
            if (responseBody.contains("\"CANCELLED\"")) return "CANCELLED";
            Thread.sleep(100);
        }
        return "TIMEOUT";
    }
}
