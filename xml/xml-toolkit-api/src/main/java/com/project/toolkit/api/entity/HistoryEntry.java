package com.project.toolkit.api.entity;

import com.project.toolkit.api.dto.GenerateRequest;

import java.time.Instant;

/** One past run, enough to show in a history list and (for "generate") replay via rerun.
 * {@code request} is null for an "infer" entry -- its original upload bytes aren't retained,
 * so rerun isn't supported for those (noted on the rerun endpoint itself). */
public record HistoryEntry(String id, String type, String jobId, Instant createdAt, GenerateRequest request) {
}
