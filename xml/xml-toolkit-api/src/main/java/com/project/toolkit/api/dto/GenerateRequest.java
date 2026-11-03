package com.project.toolkit.api.dto;

import java.util.List;

/** POST /api/generate body. Every field but {@code schemaId} is optional and falls back to the
 * engine's own defaults. */
public record GenerateRequest(
        String schemaId,
        Integer count,
        Integer level,
        String size,
        Integer errorPercent,
        List<String> errorTypes,
        Long seed,
        Integer threads,
        String output, // zip | stream -- "folder" doesn't make sense over HTTP, so not offered here
        Boolean includeSupplement,
        String profilePath // server path to a YAML name-value profile (A4); validated via PathSecurity
) {
}
