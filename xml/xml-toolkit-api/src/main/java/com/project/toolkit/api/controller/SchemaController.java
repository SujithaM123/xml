package com.project.toolkit.api.controller;

import com.project.toolkit.api.entity.SchemaRecord;
import com.project.toolkit.api.service.SchemaService;
import com.project.toolkit.generate.ScanCommand;
import com.project.toolkit.generate.SchemaScanResult;
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
import java.util.List;

@RestController
@RequestMapping("/api/schemas")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping
    public List<SchemaRecord> list() {
        return schemaService.list();
    }

    @PostMapping
    public ResponseEntity<SchemaRecord> register(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(value = "name", required = false) String name,
                                                  @RequestParam(value = "linkedSupplementId", required = false) String linkedSupplementId) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        String effectiveName = (name != null && !name.isBlank()) ? name : file.getOriginalFilename();
        SchemaRecord record = schemaService.register(effectiveName, file.getInputStream(), linkedSupplementId);
        return ResponseEntity.status(HttpStatus.CREATED).body(record);
    }

    @GetMapping("/{id}/scan")
    public SchemaScanResult scan(@PathVariable String id) {
        SchemaRecord record = find(id);
        return ScanCommand.run(schemaService.xsdPath(record));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        SchemaRecord record = find(id);
        String fileName = (record.name() != null ? record.name() : record.rootElementName()) + ".xsd";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(new FileSystemResource(schemaService.xsdPath(record)));
    }

    private SchemaRecord find(String id) {
        return schemaService.get(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such schema: " + id));
    }
}
