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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchemaControllerTest {

    @TempDir
    static Path dataDir;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
        registry.add("app.allowed-base-folders", () -> dataDir.toString());
    }

    private static final Path SEEV035 = Path.of("..", "xsd", "SR_2025_Corporate_Action_CAPA_seev_035_001_16.xsd");

    @Test
    void registersListsAndScansASchema() {
        TestRestTemplate rest = new TestRestTemplate();
        String base = "http://localhost:" + port;

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(SEEV035));
        body.add("name", "seev.035");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> registerResponse = rest.postForEntity(base + "/api/schemas",
                new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.CREATED, registerResponse.getStatusCode());
        assertTrue(registerResponse.getBody().contains("\"rootElementName\":\"Document\""));
        assertTrue(registerResponse.getBody().contains("seev.035.001.16"));

        String id = registerResponse.getBody().split("\"id\":\"")[1].split("\"")[0];

        ResponseEntity<String> listResponse = rest.getForEntity(base + "/api/schemas", String.class);
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertTrue(listResponse.getBody().contains(id));

        ResponseEntity<String> scanResponse = rest.getForEntity(base + "/api/schemas/" + id + "/scan", String.class);
        assertEquals(HttpStatus.OK, scanResponse.getStatusCode());
        assertTrue(scanResponse.getBody().contains("\"requiredCount\":136"), "scan result should match the real schema:\n" + scanResponse.getBody());
        assertTrue(scanResponse.getBody().contains("\"finiteRepeatCount\":0"));
    }

    @Test
    void scanOfUnknownSchemaReturns404() {
        TestRestTemplate rest = new TestRestTemplate();
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/api/schemas/does-not-exist/scan", String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
