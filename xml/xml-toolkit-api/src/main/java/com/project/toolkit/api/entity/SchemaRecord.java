package com.project.toolkit.api.entity;

/** One registered XSD: enough to generate/infer from it again without re-uploading. */
public record SchemaRecord(String id, String name, String fileName, String rootElementName,
                            String namespace, String version, String linkedSupplementId) {
}
