package com.project.toolkit.generate;

import java.util.Set;
import java.util.TreeSet;

/** Counts of what a given XSD actually contains, and therefore which content levels and
 * error-injection types are meaningful for it (see {@link SchemaScanner}). */
public final class SchemaScanResult {
    public int requiredCount;
    public int optionalCount;
    public int repeatingCount;
    public int finiteRepeatCount;
    public int choiceCount;
    public int enumerationCount;
    public int patternCount;
    public int maxLengthCount;
    public int anySlotCount;
    public int maxDepth;
    public int dateTypeCount;
    public int attributeCount;
    public int requiredAttributeCount;
    public int corruptibleAttributeCount;
    public final Set<Integer> possibleLevels = new TreeSet<>();
    public final Set<String> possibleErrorTypes = new TreeSet<>();

    public void print(java.io.PrintStream out) {
        out.println("  required=" + requiredCount + " optional=" + optionalCount
                + " repeating=" + repeatingCount + " (finite maxOccurs>1: " + finiteRepeatCount + ")");
        out.println("  choices=" + choiceCount + " enumerations=" + enumerationCount
                + " patterns=" + patternCount + " maxLength facets=" + maxLengthCount + " xs:any slots=" + anySlotCount);
        out.println("  max depth=" + maxDepth);
        out.println("  date-like fields=" + dateTypeCount + " attributes=" + attributeCount
                + " (required: " + requiredAttributeCount + ", corruptible: " + corruptibleAttributeCount + ")");
        out.println("  possible levels=" + possibleLevels);
        out.println("  possible error types=" + possibleErrorTypes);
    }
}
