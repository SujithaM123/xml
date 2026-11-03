package com.project.toolkit.api.entity;

import java.util.List;

/** One bucket of {@link com.project.toolkit.api.util.FileGrouper#group}: files that look like
 * the same message type. {@code fingerprint} is only meaningful (non-null) for a group whose
 * root has no namespace, where namespace+localName alone can't tell two different message
 * shapes apart. */
public record FileGroup(String groupKey, String namespace, String rootElementName,
                         String fingerprint, List<String> fileNames) {
}
