package com.project.toolkit.infer;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects xs:choice among a node's direct children: a set of siblings that never co-occur in
 * the same parent occurrence but, together, cover every occurrence (or every occurrence where
 * at least one of them is present). Children not part of any such group are left as plain
 * sequence members, unchanged from the original required/optional logic.
 */
final class ChoiceGrouper {

    sealed interface Slot permits SingleSlot, ChoiceSlot {
    }

    record SingleSlot(ElementNode child) implements Slot {
    }

    record ChoiceSlot(List<ElementNode> members, boolean required) implements Slot {
    }

    /** Orders slots by the first-seen position of their earliest member, matching the order
     * children were first encountered across all samples (same convention as the old flat loop). */
    static List<Slot> group(ElementNode parent) {
        List<ElementNode> children = parent.childrenInInferredOrder();
        Map<QName, Integer> order = new HashMap<>();
        for (int i = 0; i < children.size(); i++) {
            order.put(children.get(i).qname, i);
        }

        List<ElementNode> candidates = new ArrayList<>();
        for (ElementNode child : children) {
            if (child.presentInParentInstances > 0 && child.presentInParentInstances < parent.totalInstances) {
                candidates.add(child);
            }
        }

        int[] parentOf = new int[candidates.size()];
        for (int i = 0; i < parentOf.length; i++) {
            parentOf[i] = i;
        }
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                if (neverCoOccur(parent, candidates.get(i).qname, candidates.get(j).qname)) {
                    union(parentOf, i, j);
                }
            }
        }

        Map<Integer, List<ElementNode>> groupsByRoot = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            groupsByRoot.computeIfAbsent(find(parentOf, i), k -> new ArrayList<>()).add(candidates.get(i));
        }

        Set<ElementNode> grouped = new java.util.HashSet<>();
        List<Slot> slots = new ArrayList<>();
        for (List<ElementNode> members : groupsByRoot.values()) {
            if (members.size() < 2) {
                continue;
            }
            grouped.addAll(members);
            slots.add(new ChoiceSlot(members, isRequired(parent, members)));
        }
        for (ElementNode child : children) {
            if (!grouped.contains(child)) {
                slots.add(new SingleSlot(child));
            }
        }

        slots.sort((a, b) -> Integer.compare(firstPosition(a, order), firstPosition(b, order)));
        return slots;
    }

    private static int firstPosition(Slot slot, Map<QName, Integer> order) {
        List<ElementNode> members = slot instanceof SingleSlot s ? List.of(s.child()) : ((ChoiceSlot) slot).members();
        int min = Integer.MAX_VALUE;
        for (ElementNode m : members) {
            min = Math.min(min, order.getOrDefault(m.qname, Integer.MAX_VALUE));
        }
        return min;
    }

    private static boolean neverCoOccur(ElementNode parent, QName a, QName b) {
        if (parent.occurrenceChildSets.isEmpty()) {
            return false;
        }
        for (Set<QName> present : parent.occurrenceChildSets) {
            if (present.contains(a) && present.contains(b)) {
                return false;
            }
        }
        return true;
    }

    /** A choice group is "required" only if we have full (uncapped) occurrence data for the
     * parent and at least one member is present in every single recorded occurrence. */
    private static boolean isRequired(ElementNode parent, List<ElementNode> members) {
        if (!parent.hasCompleteOccurrenceData() || parent.occurrenceChildSets.isEmpty()) {
            return false;
        }
        Set<QName> names = new java.util.HashSet<>();
        for (ElementNode m : members) {
            names.add(m.qname);
        }
        for (Set<QName> present : parent.occurrenceChildSets) {
            boolean anyPresent = false;
            for (QName n : names) {
                if (present.contains(n)) {
                    anyPresent = true;
                    break;
                }
            }
            if (!anyPresent) {
                return false;
            }
        }
        return true;
    }

    private static int find(int[] parentOf, int i) {
        while (parentOf[i] != i) {
            i = parentOf[i];
        }
        return i;
    }

    private static void union(int[] parentOf, int i, int j) {
        int ri = find(parentOf, i);
        int rj = find(parentOf, j);
        if (ri != rj) {
            parentOf[ri] = rj;
        }
    }
}
