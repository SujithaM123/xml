package com.project.toolkit.api.dto;

import java.util.Map;

/** POST /api/infer/{jobId}/decisions body. {@code decisions} maps a field path (as given in a
 * review item) to the human's call on it, e.g. "accept", "reject", or an explicit override value. */
public record InferDecisionsRequest(String group, Map<String, String> decisions) {
}
