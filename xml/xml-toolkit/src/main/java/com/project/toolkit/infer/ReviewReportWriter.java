package com.project.toolkit.infer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes a plain-language review report so a human can sanity-check the inferred XSD. */
final class ReviewReportWriter {

    private static final int ENUM_MIN_SAMPLES = 20;
    private static final int ENUM_MAX_DISTINCT = 6;
    private static final int ENUM_MIN_DISTINCT = 2;
    private static final int ENUM_MAX_VALUE_LENGTH = 12;
    private static final int MIN_CONFIDENT_SAMPLES = 5;

    private final List<ReviewItem> reviewItems = new ArrayList<>();
    private final List<ReviewItem> autoAccepted = new ArrayList<>();

    void write(ElementNode root, int fileCount, Path outReportMd) throws IOException {
        walk(root, fileCount, "", -1);
        writeMarkdown(fileCount, outReportMd, null);
        writeJson(fileCount, jsonPathFor(outReportMd), null);
    }

    /** Baseline mode: only reports what changed versus a previously-written XSD. */
    void writeWithBaseline(ElementNode root, int fileCount, Path outReportMd, Path baselineXsd) throws IOException {
        walk(root, fileCount, "", -1);
        List<BaselineDiff.Change> changes = BaselineDiff.diff(root, baselineXsd);
        writeMarkdown(fileCount, outReportMd, changes);
        writeJson(fileCount, jsonPathFor(outReportMd), changes);
    }

    private static Path jsonPathFor(Path outReportMd) {
        String name = outReportMd.getFileName().toString();
        String base = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return outReportMd.resolveSibling(base + ".json");
    }

    private void writeMarkdown(int fileCount, Path outReport, List<BaselineDiff.Change> changes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# XSD inference review\n\n");
        if (fileCount <= 1) {
            sb.append("> **Warning:** only ").append(fileCount).append(" sample file was given. ")
                    .append("Required-vs-optional and repeat guesses below are based on a single example ")
                    .append("and may not hold for other real files -- please review carefully.\n\n");
        } else {
            sb.append("Based on ").append(fileCount).append(" sample files. ")
                    .append(autoAccepted.size()).append(" high-confidence guess(es) were auto-accepted and are not listed below.\n\n");
        }

        if (changes != null) {
            sb.append("## Changes vs. baseline\n\n");
            if (changes.isEmpty()) {
                sb.append("(no differences from the baseline XSD)\n\n");
            } else {
                for (BaselineDiff.Change c : changes) {
                    sb.append("- ").append(c.path()).append(": ").append(c.kind())
                            .append(" (").append(c.before()).append(" -> ").append(c.after()).append(")\n");
                }
                sb.append('\n');
            }
            Files.writeString(outReport, sb.toString());
            return;
        }

        appendSection(sb, "Required/optional needing confirmation", ReviewItem.Kind.REQUIRED_OPTIONAL);
        appendSection(sb, "Repeats needing confirmation", ReviewItem.Kind.REPEATS);
        appendSection(sb, "Possible choice groups (mutually exclusive fields)", ReviewItem.Kind.CHOICE);
        appendSection(sb, "Possible enumerations", ReviewItem.Kind.ENUMERATION);

        Files.writeString(outReport, sb.toString());
    }

    private void appendSection(StringBuilder sb, String title, ReviewItem.Kind kind) {
        List<ReviewItem> items = reviewItems.stream().filter(i -> i.kind() == kind).toList();
        sb.append("## ").append(title).append("\n\n");
        if (items.isEmpty()) {
            sb.append("(none)\n\n");
        } else {
            for (ReviewItem item : items) {
                sb.append(item.toMarkdownLine()).append(" [confidence: ").append(item.confidence().name().toLowerCase()).append("]\n");
            }
            sb.append('\n');
        }
    }

    private void writeJson(int fileCount, Path outReportJson, List<BaselineDiff.Change> changes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"fileCount\":").append(fileCount).append(",\n");
        if (changes != null) {
            sb.append("  \"baselineChanges\":[");
            for (int i = 0; i < changes.size(); i++) {
                BaselineDiff.Change c = changes.get(i);
                sb.append(i == 0 ? "" : ",").append("\n    {\"path\":\"").append(escape(c.path()))
                        .append("\",\"kind\":\"").append(escape(c.kind()))
                        .append("\",\"before\":\"").append(escape(c.before()))
                        .append("\",\"after\":\"").append(escape(c.after())).append("\"}");
            }
            sb.append(changes.isEmpty() ? "]\n" : "\n  ]\n");
            sb.append("}\n");
            Files.writeString(outReportJson, sb.toString());
            return;
        }

        sb.append("  \"needsReview\":[");
        for (int i = 0; i < reviewItems.size(); i++) {
            sb.append(i == 0 ? "" : ",").append("\n    ").append(reviewItems.get(i).toJson());
        }
        sb.append(reviewItems.isEmpty() ? "]" : "\n  ]").append(",\n");
        sb.append("  \"autoAccepted\":[");
        for (int i = 0; i < autoAccepted.size(); i++) {
            sb.append(i == 0 ? "" : ",").append("\n    ").append(autoAccepted.get(i).toJson());
        }
        sb.append(autoAccepted.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        Files.writeString(outReportJson, sb.toString());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void walk(ElementNode node, int fileCount, String path, int parentTotalInstances) {
        String name = path.isEmpty() ? node.qname.getLocalPart() : path + "/" + node.qname.getLocalPart();
        boolean isRoot = parentTotalInstances < 0;

        if (!isRoot) {
            noteRequiredOptional(name, node, parentTotalInstances);
        }
        if (node.repeats()) {
            noteRepeats(name, node, fileCount);
        }
        noteEnumCandidate(name, node.qname.getLocalPart(), node.textSamples);

        for (ElementNode.AttrNode attr : node.attributes.values()) {
            String attrPath = name + "/@" + attr.name;
            noteAttrRequiredOptional(attrPath, attr, node.totalInstances);
            noteEnumCandidate(attrPath, attr.name, attr.valueSamples);
        }

        for (ChoiceGrouper.Slot slot : ChoiceGrouper.group(node)) {
            if (slot instanceof ChoiceGrouper.ChoiceSlot choice) {
                noteChoice(name, choice);
            }
        }

        for (ElementNode child : node.children.values()) {
            walk(child, fileCount, name, node.totalInstances);
        }
    }

    /** Required/optional is judged purely against the immediate parent's own occurrence count --
     * a child present every time its parent occurs is required, no question, and isn't listed.
     * An optional call is itself only "in doubt" (shown) when the sample size is small or the
     * split is a near-unanimous 1-occurrence outlier; a clean, well-sampled partial presence is
     * exactly what minOccurs="0" means and needs no confirmation. */
    private void noteRequiredOptional(String path, ElementNode node, int parentTotalInstances) {
        boolean required = node.isRequired(parentTotalInstances);
        if (required) {
            return;
        }
        int present = node.presentInParentInstances;
        int absent = parentTotalInstances - present;
        ReviewItem.Confidence confidence;
        if (parentTotalInstances < MIN_CONFIDENT_SAMPLES) {
            confidence = ReviewItem.Confidence.LOW;
        } else if (present <= 1 || absent <= 1) {
            confidence = ReviewItem.Confidence.MEDIUM;
        } else {
            confidence = ReviewItem.Confidence.HIGH;
        }
        String evidence = "present in " + present + " of " + parentTotalInstances + " occurrences of its parent";
        ReviewItem item = new ReviewItem(path, ReviewItem.Kind.REQUIRED_OPTIONAL, "optional", evidence, confidence);
        file(item);
    }

    private void noteAttrRequiredOptional(String path, ElementNode.AttrNode attr, int ownerTotalInstances) {
        boolean required = attr.isRequired(ownerTotalInstances);
        if (required) {
            return;
        }
        int absent = ownerTotalInstances - attr.presentCount;
        ReviewItem.Confidence confidence;
        if (ownerTotalInstances < MIN_CONFIDENT_SAMPLES) {
            confidence = ReviewItem.Confidence.LOW;
        } else if (attr.presentCount <= 1 || absent <= 1) {
            confidence = ReviewItem.Confidence.MEDIUM;
        } else {
            confidence = ReviewItem.Confidence.HIGH;
        }
        String evidence = "present on " + attr.presentCount + " of " + ownerTotalInstances + " occurrences";
        file(new ReviewItem(path, ReviewItem.Kind.REQUIRED_OPTIONAL, "optional attribute", evidence, confidence));
    }

    private void noteRepeats(String path, ElementNode node, int fileCount) {
        ReviewItem.Confidence confidence = node.totalInstances < MIN_CONFIDENT_SAMPLES
                ? ReviewItem.Confidence.LOW : ReviewItem.Confidence.HIGH;
        String evidence = "repeats up to " + node.maxRepeatPerInstance + " times in a single occurrence";
        file(new ReviewItem(path, ReviewItem.Kind.REPEATS, "maxOccurs=unbounded", evidence, confidence));
    }

    private void noteChoice(String parentPath, ChoiceGrouper.ChoiceSlot choice) {
        StringBuilder names = new StringBuilder();
        for (ElementNode member : choice.members()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(member.qname.getLocalPart());
        }
        ReviewItem.Confidence confidence = choice.required() ? ReviewItem.Confidence.MEDIUM : ReviewItem.Confidence.LOW;
        file(new ReviewItem(parentPath, ReviewItem.Kind.CHOICE, "xs:choice(" + names + ")",
                "these fields never appear together in the same occurrence", confidence));
    }

    private void noteEnumCandidate(String path, String localName, List<String> samples) {
        if (TagNameRules.isIdentifierLike(localName)) {
            return;
        }
        if (FormatDetector.detect(samples) != FormatDetector.Format.NONE) {
            // A recognized format (ISIN/BIC/LEI/currency/country/date) is a stronger, more useful
            // claim than "looks like an enum" and is auto-accepted elsewhere; don't also suggest
            // treating it as a closed value list.
            return;
        }
        String guessedType = DatatypeGuesser.guess(samples, localName);
        if (guessedType.equals("decimal") || guessedType.equals("integer") || guessedType.equals("long")
                || guessedType.equals("date") || guessedType.equals("dateTime") || guessedType.equals("boolean")) {
            return;
        }
        if (samples.size() < ENUM_MIN_SAMPLES) {
            return;
        }
        Set<String> distinct = new LinkedHashSet<>(samples);
        if (distinct.size() < ENUM_MIN_DISTINCT || distinct.size() > ENUM_MAX_DISTINCT) {
            return;
        }
        for (String v : distinct) {
            if (v.length() > ENUM_MAX_VALUE_LENGTH) {
                return;
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : samples) {
            counts.merge(s, 1, Integer::sum);
        }
        for (int c : counts.values()) {
            if (c < 2) {
                return;
            }
        }
        String evidence = samples.size() + " samples, " + distinct.size() + " distinct values seen, each at least twice";
        file(new ReviewItem(path, ReviewItem.Kind.ENUMERATION, "enum(" + String.join(", ", distinct) + ")", evidence, ReviewItem.Confidence.MEDIUM));
    }

    private void file(ReviewItem item) {
        if (item.confidence() == ReviewItem.Confidence.HIGH) {
            autoAccepted.add(item);
        } else {
            reviewItems.add(item);
        }
    }
}
