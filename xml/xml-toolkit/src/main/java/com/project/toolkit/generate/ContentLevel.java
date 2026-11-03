package com.project.toolkit.generate;

/**
 * How much of the schema's optional content a generated document includes.
 * {@code --size} only has an effect at {@link #FULL} -- the other two levels always write
 * exactly one occurrence of any repeating element, so there's no size tier to calibrate.
 */
public enum ContentLevel {
    /** Required elements/attributes only (minOccurs&gt;=1), one occurrence each. */
    REQUIRED_ONLY(1),
    /** Required and optional, but still one occurrence of anything that repeats. */
    REQUIRED_AND_OPTIONAL(2),
    /** Required, optional, and repetitions -- the original behavior, sized by {@code --size}. */
    FULL(3);

    public final int number;

    ContentLevel(int number) {
        this.number = number;
    }

    public static ContentLevel fromNumber(int n) {
        for (ContentLevel level : values()) {
            if (level.number == n) {
                return level;
            }
        }
        throw new IllegalArgumentException("--level must be 1, 2, or 3, got: " + n);
    }
}
