package com.project.toolkit.api.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.toolkit.api.config.AppProperties;
import com.project.toolkit.api.entity.SchemaRecord;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Pure file-based persistence for registered schemas: the in-memory index, the registry.json
 * backing file, and the on-disk storage of each uploaded XSD's bytes. No business rules here
 * (version derivation, id generation) -- that's {@link com.project.toolkit.api.service.SchemaService}. */
@Repository
public final class SchemaRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, SchemaRecord> schemas = new ConcurrentHashMap<>();
    private final Path storageDir;
    private final Path registryFile;

    public SchemaRepository(AppProperties properties) throws IOException {
        Path dataDir = Path.of(properties.getDataDir());
        this.storageDir = dataDir.resolve("schemas");
        this.registryFile = dataDir.resolve("registry.json");
        Files.createDirectories(storageDir);
        load();
    }

    /** Copies the uploaded XSD's bytes to disk under {@code id} and returns the stored file name. */
    public String storeXsdFile(String id, InputStream content) throws IOException {
        String fileName = id + ".xsd";
        Files.copy(content, storageDir.resolve(fileName));
        return fileName;
    }

    public void save(SchemaRecord record) throws IOException {
        schemas.put(record.id(), record);
        persist();
    }

    public List<SchemaRecord> findAll() {
        return List.copyOf(schemas.values());
    }

    public Optional<SchemaRecord> findById(String id) {
        return Optional.ofNullable(schemas.get(id));
    }

    public Path resolveXsdPath(SchemaRecord record) {
        return resolveXsdPath(record.fileName());
    }

    public Path resolveXsdPath(String fileName) {
        return storageDir.resolve(fileName);
    }

    private void persist() throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(registryFile.toFile(), List.copyOf(schemas.values()));
    }

    private void load() throws IOException {
        if (!Files.exists(registryFile)) {
            return;
        }
        SchemaRecord[] records = MAPPER.readValue(registryFile.toFile(), SchemaRecord[].class);
        for (SchemaRecord r : records) {
            schemas.put(r.id(), r);
        }
    }
}
