package com.project.toolkit.api.service;

import com.project.toolkit.api.entity.SchemaRecord;
import com.project.toolkit.api.repository.SchemaRepository;
import com.project.toolkit.generate.SchemaInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Registered XSDs (GET /api/schemas, generate/infer by schemaId, history re-run): id generation,
 * version derivation from namespace, and orchestrating storage via {@link SchemaRepository}. */
@Service
public final class SchemaService {

    private static final Pattern VERSION_SUFFIX = Pattern.compile("(\\d{3}\\.\\d{2,3})$");

    private final SchemaRepository repository;

    public SchemaService(SchemaRepository repository) {
        this.repository = repository;
    }

    public SchemaRecord register(String name, InputStream xsdContent, String linkedSupplementId) throws IOException {
        String id = UUID.randomUUID().toString();
        String fileName = repository.storeXsdFile(id, xsdContent);
        Path dest = repository.resolveXsdPath(fileName);

        SchemaInfo info = SchemaInfo.read(dest);
        String version = deriveVersion(info.namespace);
        SchemaRecord record = new SchemaRecord(id, name, fileName, info.rootElementName, info.namespace, version, linkedSupplementId);
        repository.save(record);
        return record;
    }

    public List<SchemaRecord> list() {
        return repository.findAll();
    }

    public Optional<SchemaRecord> get(String id) {
        return repository.findById(id);
    }

    public Path xsdPath(SchemaRecord record) {
        return repository.resolveXsdPath(record);
    }

    private static String deriveVersion(String namespace) {
        Matcher m = VERSION_SUFFIX.matcher(namespace);
        return m.find() ? m.group(1) : null;
    }
}
