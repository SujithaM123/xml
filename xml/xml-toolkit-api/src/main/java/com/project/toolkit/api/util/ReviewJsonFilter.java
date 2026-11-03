package com.project.toolkit.api.util;

import java.util.Map;
import java.util.regex.Pattern;

/** Strips already-decided fields out of a review report's JSON text (the API spec's "apply
 * automatically next time" rule) without re-parsing it into objects, since the report is already
 * exactly the shape the caller wants. */
public final class ReviewJsonFilter {

    private ReviewJsonFilter() {
    }

    public static String filterOutDecidedPaths(String json, Map<String, String> existingDecisions) {
        String result = json;
        for (String decidedPath : existingDecisions.keySet()) {
            result = result.replaceAll(",?\\s*\\{[^{}]*\"path\":\"" + Pattern.quote(decidedPath) + "\"[^{}]*}", "");
        }
        return result;
    }
}
