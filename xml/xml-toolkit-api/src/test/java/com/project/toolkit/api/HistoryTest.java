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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HistoryTest {

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
    void historyRecordsAGenerateRunAndRerunCreatesANewJob() throws InterruptedException {
        TestRestTemplate rest = new TestRestTemplate();
        String base = "http://localhost:" + port;

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(SUPL009));
        body.add("name", "supl.009-history-test");
        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> schemaResponse = rest.postForEntity(base + "/api/schemas", new HttpEntity<>(body, multipartHeaders), String.class);
        String schemaId = schemaResponse.getBody().split("\"id\":\"")[1].split("\"")[0];

        String genBody = """
                {"schemaId":"%s","count":3,"level":1,"seed":55}
                """.formatted(schemaId);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> genResponse = rest.postForEntity(base + "/api/generate", new HttpEntity<>(genBody, jsonHeaders), String.class);
        String firstJobId = genResponse.getBody().split("\"jobId\":\"")[1].split("\"")[0];
        waitForCompletion(rest, base, firstJobId);

        ResponseEntity<String> historyResponse = rest.getForEntity(base + "/api/history", String.class);
        assertEquals(HttpStatus.OK, historyResponse.getStatusCode());
        assertTrue(historyResponse.getBody().contains(firstJobId), "history should list the run we just made:\n" + historyResponse.getBody());
        String historyId = historyResponse.getBody().split("\"id\":\"")[1].split("\"")[0];

        ResponseEntity<String> rerunResponse = rest.postForEntity(base + "/api/history/" + historyId + "/rerun", null, String.class);
        assertEquals(HttpStatus.ACCEPTED, rerunResponse.getStatusCode());
        String secondJobId = rerunResponse.getBody().split("\"jobId\":\"")[1].split("\"")[0];
        assertNotEquals(firstJobId, secondJobId, "rerun should create a distinct new job");
        waitForCompletion(rest, base, secondJobId);
    }

    private void waitForCompletion(TestRestTemplate rest, String base, String jobId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            String body = rest.getForEntity(base + "/api/jobs/" + jobId, String.class).getBody();
            if (body.contains("\"SUCCEEDED\"") || body.contains("\"FAILED\"")) {
                return;
            }
            Thread.sleep(100);
        }
        fail("job " + jobId + " did not finish in time");
    }
}
