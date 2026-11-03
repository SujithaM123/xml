package com.project.toolkit.api;

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

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerateJobFlowTest {

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
    void generateJobRunsAndProducesADownloadableZipWithAReport() throws Exception {
        TestRestTemplate rest = new TestRestTemplate();
        String base = "http://localhost:" + port;

        String schemaId = registerSchema(rest, base);

        String body = """
                {"schemaId":"%s","count":5,"level":2,"seed":123}
                """.formatted(schemaId);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> genResponse = rest.postForEntity(base + "/api/generate",
                new HttpEntity<>(body, jsonHeaders), String.class);
        assertEquals(HttpStatus.ACCEPTED, genResponse.getStatusCode());
        String jobId = genResponse.getBody().split("\"jobId\":\"")[1].split("\"")[0];

        String status = waitForCompletion(rest, base, jobId);
        assertEquals("SUCCEEDED", status, "job should succeed");

        ResponseEntity<byte[]> download = rest.getForEntity(base + "/api/jobs/" + jobId + "/download", byte[].class);
        assertEquals(HttpStatus.OK, download.getStatusCode());
        int xmlEntries = 0;
        int totalEntries = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(download.getBody()))) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                totalEntries++;
                if (entry.getName().endsWith(".xml")) {
                    xmlEntries++;
                }
                assertFalse(entry.getName().startsWith("report.") || entry.getName().startsWith("manifest."),
                        "the downloaded zip should contain only XML documents, not " + entry.getName());
                entry = zis.getNextEntry();
            }
        }
        assertEquals(5, xmlEntries, "zip should contain the 5 requested XML files");
        assertEquals(5, totalEntries, "zip should contain nothing but the 5 XML files");

        ResponseEntity<String> report = rest.getForEntity(base + "/api/jobs/" + jobId + "/report?format=json", String.class);
        assertEquals(HttpStatus.OK, report.getStatusCode());
        assertTrue(report.getBody().contains("\"seed\":123"), "report should echo the seed used:\n" + report.getBody());

        ResponseEntity<String> csvReport = rest.getForEntity(base + "/api/jobs/" + jobId + "/report?format=csv", String.class);
        assertEquals(HttpStatus.OK, csvReport.getStatusCode());
        assertTrue(csvReport.getBody().contains("seed,123"));
    }

    @Test
    void cancellingAJobStopsItFromReportingSuccess() throws Exception {
        TestRestTemplate rest = new TestRestTemplate();
        String base = "http://localhost:" + port;
        String schemaId = registerSchema(rest, base);

        String body = """
                {"schemaId":"%s","count":2000,"level":3,"size":"large","seed":7,"threads":1}
                """.formatted(schemaId);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> genResponse = rest.postForEntity(base + "/api/generate",
                new HttpEntity<>(body, jsonHeaders), String.class);
        String jobId = genResponse.getBody().split("\"jobId\":\"")[1].split("\"")[0];

        Thread.sleep(300); // let it start and queue a few files before cancelling
        ResponseEntity<String> cancelResponse = rest.postForEntity(base + "/api/jobs/" + jobId + "/cancel", null, String.class);
        assertEquals(HttpStatus.OK, cancelResponse.getStatusCode());
        assertTrue(cancelResponse.getBody().contains("\"cancelled\":true"));

        String status = waitForCompletion(rest, base, jobId);
        assertEquals("CANCELLED", status);
    }

    private String registerSchema(TestRestTemplate rest, String base) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(SUPL009));
        body.add("name", "supl.009");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = rest.postForEntity(base + "/api/schemas", new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody().split("\"id\":\"")[1].split("\"")[0];
    }

    private String waitForCompletion(TestRestTemplate rest, String base, String jobId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<String> statusResponse = rest.getForEntity(base + "/api/jobs/" + jobId, String.class);
            String body = statusResponse.getBody();
            if (body.contains("\"SUCCEEDED\"") || body.contains("\"FAILED\"") || body.contains("\"CANCELLED\"")) {
                if (body.contains("\"SUCCEEDED\"")) return "SUCCEEDED";
                if (body.contains("\"FAILED\"")) return "FAILED: " + body;
                return "CANCELLED";
            }
            Thread.sleep(100);
        }
        return "TIMEOUT";
    }
}
