package com.project.toolkit.infer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the merged {@link ElementNode} tree as a plain hand-readable XSD: one named
 * complexType per element that has children or attributes, xs:sequence preserving the
 * order elements were first seen in, and inline xs:string/xs:decimal/... for leaves.
 */
final class XsdWriter {

    private final Map<ElementNode, String> typeNames = new IdentityHashMap<>();
    private final Map<String, Integer> nameCounts = new HashMap<>();
    private final List<String> complexTypeBlocks = new ArrayList<>();
    private final Map<String, String> typeByStructuralKey = new HashMap<>();
    private String typePrefix = "";
    private String schemaNamespace = "";
    private int typeCountBeforeMerge;

    /** Number of element positions that needed a named complexType, before deduplicating
     * structurally identical ones -- set after {@link #write}. */
    int typeCountBeforeMerge() {
        return typeCountBeforeMerge;
    }

    /** Final number of distinct named complexTypes actually written -- set after {@link #write}. */
    int typeCountAfterMerge() {
        return complexTypeBlocks.size();
    }

    void write(ElementNode root, Path outXsd) throws IOException {
        String ns = root.qname.getNamespaceURI();
        schemaNamespace = ns == null ? "" : ns;
        if (ns != null && !ns.isEmpty()) {
            // Named complexTypes live in the target namespace, so references to them need the
            // "tns:" prefix -- an unprefixed type="DocumentType" would resolve to no namespace.
            typePrefix = "tns:";
        }
        assignTypes(root);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"");
        if (ns != null && !ns.isEmpty()) {
            sb.append(" xmlns:tns=\"").append(ns).append("\"")
                    .append(" targetNamespace=\"").append(ns).append("\"")
                    .append(" elementFormDefault=\"qualified\"");
        }
        sb.append(">\n\n");

        String rootTypeRef = typeNames.containsKey(root)
                ? typePrefix + typeNames.get(root)
                : "xs:" + DatatypeGuesser.guess(root.textSamples, root.qname.getLocalPart());
        sb.append("  <xs:element name=\"").append(root.qname.getLocalPart())
                .append("\" type=\"").append(rootTypeRef).append("\"/>\n\n");

        for (String block : complexTypeBlocks) {
            sb.append(block).append('\n');
        }
        sb.append("</xs:schema>\n");

        Files.writeString(outXsd, sb.toString());
    }

    private void assignTypes(ElementNode node) {
        if (typeNames.containsKey(node)) {
            return;
        }
        for (ElementNode child : node.children.values()) {
            assignTypes(child);
        }
        if (node.hasChildren || !node.attributes.isEmpty()) {
            typeCountBeforeMerge++;
            // Computed after children are resolved above, so it already reflects any merging
            // done lower in the tree -- two parents whose children turned out to share a merged
            // type can now also be recognized as structurally identical to each other.
            String key = structuralKey(node);
            String existing = typeByStructuralKey.get(key);
            if (existing != null) {
                typeNames.put(node, existing);
                return;
            }
            String typeName = newTypeName(node.qname.getLocalPart());
            typeNames.put(node, typeName);
            typeByStructuralKey.put(key, typeName);
            complexTypeBlocks.add(renderComplexType(typeName, node));
        }
    }

    /** Same shape used for rendering (hasChildren/simpleContent, slot grouping, attributes), so
     * two nodes get the same key exactly when {@link #renderComplexType} would emit the same
     * complexType body for them -- that's the definition of "structurally identical" used here. */
    private String structuralKey(ElementNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.hasChildren) {
            sb.append("SEQ[");
            for (ChoiceGrouper.Slot slot : ChoiceGrouper.group(node)) {
                if (slot instanceof ChoiceGrouper.SingleSlot single) {
                    sb.append(elementKey(single.child(), node, true));
                } else {
                    ChoiceGrouper.ChoiceSlot choice = (ChoiceGrouper.ChoiceSlot) slot;
                    sb.append("CHOICE(").append(choice.required()).append(")[");
                    for (ElementNode member : choice.members()) {
                        sb.append(elementKey(member, node, false));
                    }
                    sb.append(']');
                }
            }
            sb.append(']');
        } else {
            sb.append("SIMPLE(").append(DatatypeGuesser.guess(node.textSamples, node.qname.getLocalPart())).append(')');
        }
        sb.append("ATTRS[");
        for (ElementNode.AttrNode attr : node.attributes.values()) {
            sb.append(attr.name).append(':').append(DatatypeGuesser.guess(attr.valueSamples, attr.name))
                    .append(':').append(attr.isRequired(node.totalInstances)).append(';');
        }
        sb.append(']');
        return sb.toString();
    }

    private String elementKey(ElementNode child, ElementNode parent, boolean withMinOccurs) {
        String typeKey = typeNames.containsKey(child)
                ? typeNames.get(child)
                : "xs:" + DatatypeGuesser.guess(child.textSamples, child.qname.getLocalPart());
        boolean required = withMinOccurs && child.isRequired(parent.totalInstances);
        return child.qname.getLocalPart() + ":" + typeKey + ":" + required + ":" + child.repeats() + ";";
    }

    private String newTypeName(String local) {
        String base = capitalize(local) + "Type";
        int n = nameCounts.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "_" + n;
    }

    private String renderComplexType(String typeName, ElementNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <xs:complexType name=\"").append(typeName).append("\">\n");

        if (node.hasChildren) {
            sb.append("    <xs:sequence>\n");
            for (ChoiceGrouper.Slot slot : ChoiceGrouper.group(node)) {
                if (slot instanceof ChoiceGrouper.SingleSlot single) {
                    appendElementRef(sb, single.child(), node, "      ", true);
                } else {
                    ChoiceGrouper.ChoiceSlot choice = (ChoiceGrouper.ChoiceSlot) slot;
                    sb.append("      <xs:choice");
                    if (!choice.required()) {
                        sb.append(" minOccurs=\"0\"");
                    }
                    sb.append(" maxOccurs=\"unbounded\">\n");
                    for (ElementNode member : choice.members()) {
                        appendElementRef(sb, member, node, "        ", false);
                    }
                    sb.append("      </xs:choice>\n");
                }
            }
            sb.append("    </xs:sequence>\n");
            appendAttributes(sb, node);
        } else {
            String base = "xs:" + DatatypeGuesser.guess(node.textSamples, node.qname.getLocalPart());
            sb.append("    <xs:simpleContent>\n");
            sb.append("      <xs:extension base=\"").append(base).append("\">\n");
            for (ElementNode.AttrNode attr : node.attributes.values()) {
                appendAttribute(sb, attr, node.totalInstances, "        ");
            }
            sb.append("      </xs:extension>\n");
            sb.append("    </xs:simpleContent>\n");
        }

        sb.append("  </xs:complexType>\n");
        return sb.toString();
    }

    /** Renders one xs:element reference. {@code withMinOccurs} is false for a choice member,
     * since the enclosing xs:choice already carries the optionality for the whole group. */
    private void appendElementRef(StringBuilder sb, ElementNode child, ElementNode parent, String indent, boolean withMinOccurs) {
        String typeRef = typeNames.containsKey(child)
                ? typePrefix + typeNames.get(child)
                : "xs:" + DatatypeGuesser.guess(child.textSamples, child.qname.getLocalPart());
        sb.append(indent).append("<xs:element name=\"").append(child.qname.getLocalPart())
                .append("\" type=\"").append(typeRef).append("\"");
        String childNs = child.qname.getNamespaceURI();
        if (!schemaNamespace.isEmpty() && (childNs == null || childNs.isEmpty())) {
            // Observed unqualified in the samples (e.g. a filler written inside an xs:any slot)
            // even though the schema otherwise declares elementFormDefault="qualified".
            sb.append(" form=\"unqualified\"");
        }
        if (withMinOccurs && !child.isRequired(parent.totalInstances)) {
            sb.append(" minOccurs=\"0\"");
        }
        if (child.repeats()) {
            sb.append(" maxOccurs=\"unbounded\"");
        }
        sb.append("/>\n");
    }

    private void appendAttributes(StringBuilder sb, ElementNode node) {
        for (ElementNode.AttrNode attr : node.attributes.values()) {
            appendAttribute(sb, attr, node.totalInstances, "    ");
        }
    }

    private void appendAttribute(StringBuilder sb, ElementNode.AttrNode attr, int ownerTotalInstances, String indent) {
        String type = "xs:" + DatatypeGuesser.guess(attr.valueSamples, attr.name);
        boolean required = attr.isRequired(ownerTotalInstances);
        sb.append(indent).append("<xs:attribute name=\"").append(attr.name)
                .append("\" type=\"").append(type).append("\"")
                .append(" use=\"").append(required ? "required" : "optional").append("\"/>\n");
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
