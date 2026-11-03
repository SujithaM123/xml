package com.project.toolkit.generate;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregate stats for one `generate` run, printed as the end-of-run summary. */
public final class GenerateResult {

    public final Map<SizeTier, Integer> countPerTier = new EnumMap<>(SizeTier.class);
    /** Deliberately-invalid files written by error injection (A6), by error type -- separate
     * from {@link #invalidCount}, which means "failed validation after 5 retries despite trying
     * to be valid" and is always 0 when error injection isn't in use. */
    public final Map<String, Integer> errorCountByType = new LinkedHashMap<>();
    public int validCount;
    public int invalidCount;
    public long totalBytes;
    public long minBytes = Long.MAX_VALUE;
    public long maxBytes = 0;
    public long elapsedMillis;
    public int totalRequested;

    public GenerateResult() {
        for (SizeTier tier : SizeTier.values()) {
            countPerTier.put(tier, 0);
        }
    }

    public double xmlsPerSecond() {
        if (elapsedMillis <= 0) {
            return 0;
        }
        return (validCount + invalidCount) / (elapsedMillis / 1000.0);
    }

    public int writtenCount() {
        return validCount + errorCountByType.values().stream().mapToInt(Integer::intValue).sum();
    }

    public double avgBytes() {
        return writtenCount() == 0 ? 0 : (double) totalBytes / writtenCount();
    }

    public void noteFileSize(long bytes) {
        totalBytes += bytes;
        minBytes = Math.min(minBytes, bytes);
        maxBytes = Math.max(maxBytes, bytes);
    }

    public void print(java.io.PrintStream out) {
        out.println("  small=" + countPerTier.get(SizeTier.SMALL)
                + " medium=" + countPerTier.get(SizeTier.MEDIUM)
                + " large=" + countPerTier.get(SizeTier.LARGE));
        out.println("  valid=" + validCount + " invalid=" + invalidCount);
        if (!errorCountByType.isEmpty()) {
            out.println("  deliberately-invalid (error injection) by type: " + errorCountByType);
        }
        out.printf("  total size on disk: %.2f MB%n", totalBytes / (1024.0 * 1024.0));
        if (writtenCount() > 0) {
            out.printf("  size per file: min=%.2f KB avg=%.2f KB max=%.2f KB%n",
                    minBytes / 1024.0, avgBytes() / 1024.0, maxBytes / 1024.0);
        }
        out.printf("  time: %.2f s  (%.1f XMLs/sec)%n", elapsedMillis / 1000.0, xmlsPerSecond());
    }
}
