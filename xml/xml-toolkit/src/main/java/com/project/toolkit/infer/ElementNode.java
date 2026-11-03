package com.project.toolkit.infer;

import javax.xml.namespace.QName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregated, cross-file statistics for one element position in the inferred tree.
 * {@code totalInstances} / {@code presentInParentInstances} are counted per-occurrence
 * (not per-file), so repeated elements and optionality are both order-independent of how
 * the samples were scanned.
 */
final class ElementNode {

    static final int MAX_SAMPLES = 300;

    final QName qname;
    final Map<QName, ElementNode> children = new LinkedHashMap<>();
    final Map<String, AttrNode> attributes = new LinkedHashMap<>();
    final List<String> textSamples = new ArrayList<>();
    /** One entry per occurrence of this node, holding which direct children were present --
     * used to detect xs:choice (children that never co-occur but collectively cover every
     * occurrence). Capped like textSamples since only a bounded sample is needed to decide. */
    final List<Set<QName>> occurrenceChildSets = new ArrayList<>();
    /** key comes before value, observed in at least one occurrence -- used to recover the real
     * declared sequence order, since samples can discover an optional early child late (the
     * first-scanned sample may simply be missing it) and plain first-seen order would then put
     * it after children that must actually come before it. */
    final Map<QName, Set<QName>> before = new HashMap<>();

    int totalInstances;
    int presentInParentInstances;
    int maxRepeatPerInstance = 1;
    boolean hasChildren;
    int filesPresentIn;

    ElementNode(QName qname) {
        this.qname = qname;
    }

    void addTextSample(String text) {
        if (textSamples.size() < MAX_SAMPLES) {
            textSamples.add(text);
        }
    }

    void addOccurrenceChildSet(Set<QName> present) {
        if (occurrenceChildSets.size() < MAX_SAMPLES) {
            occurrenceChildSets.add(new LinkedHashSet<>(present));
        }
    }

    /** True only if every recorded occurrence of this node was captured (the cap wasn't hit),
     * so choice coverage computed from {@link #occurrenceChildSets} is exact, not a sample. */
    boolean hasCompleteOccurrenceData() {
        return occurrenceChildSets.size() == totalInstances;
    }

    void addOrderEdges(List<QName> orderedDistinctChildren) {
        for (int i = 0; i < orderedDistinctChildren.size(); i++) {
            QName a = orderedDistinctChildren.get(i);
            Set<QName> after = before.computeIfAbsent(a, k -> new LinkedHashSet<>());
            for (int j = i + 1; j < orderedDistinctChildren.size(); j++) {
                after.add(orderedDistinctChildren.get(j));
            }
        }
    }

    /** Topologically sorts {@link #children} using the "before" edges collected from every
     * sample, falling back to first-seen (map insertion) order wherever samples gave no
     * constraint or disagreed (a cycle), so the result degrades gracefully to the old behavior
     * rather than failing. */
    List<ElementNode> childrenInInferredOrder() {
        List<QName> firstSeen = new ArrayList<>(children.keySet());
        Map<QName, Integer> indegree = new HashMap<>();
        for (QName q : firstSeen) {
            indegree.put(q, 0);
        }
        for (QName a : firstSeen) {
            for (QName b : before.getOrDefault(a, Set.of())) {
                if (indegree.containsKey(b)) {
                    indegree.merge(b, 1, Integer::sum);
                }
            }
        }

        List<QName> ordered = new ArrayList<>();
        Set<QName> placed = new HashSet<>();
        Deque<QName> remaining = new ArrayDeque<>(firstSeen);
        while (!remaining.isEmpty()) {
            QName next = null;
            for (QName q : remaining) {
                if (indegree.get(q) == 0) {
                    next = q;
                    break;
                }
            }
            if (next == null) {
                // Cycle (conflicting order evidence across samples): take the earliest
                // first-seen remaining node rather than getting stuck.
                next = remaining.peek();
            }
            ordered.add(next);
            placed.add(next);
            remaining.remove(next);
            for (QName b : before.getOrDefault(next, Set.of())) {
                if (indegree.containsKey(b) && !placed.contains(b)) {
                    indegree.merge(b, -1, Integer::sum);
                }
            }
        }

        List<ElementNode> result = new ArrayList<>(ordered.size());
        for (QName q : ordered) {
            result.add(children.get(q));
        }
        return result;
    }

    boolean isRequired(int parentTotalInstances) {
        return presentInParentInstances == parentTotalInstances && presentInParentInstances > 0;
    }

    boolean repeats() {
        return maxRepeatPerInstance > 1;
    }

    static final class AttrNode {
        final String name;
        final List<String> valueSamples = new ArrayList<>();
        int presentCount;

        AttrNode(String name) {
            this.name = name;
        }

        void addSample(String value) {
            if (valueSamples.size() < MAX_SAMPLES) {
                valueSamples.add(value);
            }
        }

        boolean isRequired(int ownerTotalInstances) {
            return presentCount == ownerTotalInstances && presentCount > 0;
        }
    }
}
