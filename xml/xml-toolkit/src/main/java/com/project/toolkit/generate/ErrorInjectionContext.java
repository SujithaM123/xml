package com.project.toolkit.generate;

/**
 * Drives the two-pass error injection used by {@link XmlInstanceGenerator}: pass 1 runs with
 * {@code targetIndex == -1} purely to count how many points in this specific document are
 * eligible for the chosen error type (eligibility depends on the random structure actually
 * generated, not just what the schema allows in general); pass 2 re-runs with the SAME seed
 * (so it reproduces the identical document up to the injection point) and {@code targetIndex}
 * set to a point chosen from that count, so exactly one occurrence is corrupted and the rest of
 * the document stays exactly as it would have been without injection.
 */
final class ErrorInjectionContext {

    final String errorType;
    final int targetIndex;
    private int seenCount;
    boolean applied;
    String elementPath;
    String originalValue;
    String injectedValue;

    ErrorInjectionContext(String errorType, int targetIndex) {
        this.errorType = errorType;
        this.targetIndex = targetIndex;
    }

    static ErrorInjectionContext countingPass(String errorType) {
        return new ErrorInjectionContext(errorType, -1);
    }

    int eligibleCount() {
        return seenCount;
    }

    boolean isEligible(String type) {
        return errorType.equals(type);
    }

    /** Call once per eligible occurrence found while walking; returns true at most once per
     * document, exactly at the chosen occurrence, and only during the injection pass. */
    boolean shouldInjectThisOccurrence() {
        int index = seenCount++;
        if (applied || targetIndex < 0) {
            return false;
        }
        return index == targetIndex;
    }

    void recordApplied(String elementPath, String originalValue, String injectedValue) {
        this.applied = true;
        this.elementPath = elementPath;
        this.originalValue = originalValue;
        this.injectedValue = injectedValue;
    }
}
