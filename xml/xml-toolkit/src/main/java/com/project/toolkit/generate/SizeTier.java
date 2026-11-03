package com.project.toolkit.generate;

/**
 * The three output size tiers. {@code repeatBoost} controls how many extra copies of a
 * maxOccurs&gt;1 element get written; it is damped per nesting level so repeats inside
 * repeats don't explode combinatorially.
 */
public enum SizeTier {
    SMALL(2_000, 1),
    MEDIUM(50_000, 3),
    LARGE(1_000_000, 6);

    final long targetBytes;
    final int initialRepeatFactor;

    SizeTier(long targetBytes, int initialRepeatFactor) {
        this.targetBytes = targetBytes;
        this.initialRepeatFactor = initialRepeatFactor;
    }

    public String label() {
        return name().toLowerCase();
    }
}
