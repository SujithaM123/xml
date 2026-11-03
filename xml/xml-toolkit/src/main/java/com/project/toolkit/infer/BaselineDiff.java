package com.project.toolkit.infer;

import org.apache.ws.commons.schema.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diffs the freshly-inferred tree against a previously-written XSD (typically one this tool
 * wrote on an earlier run), reporting only what changed. Only understands the shape this tool's
 * own {@link XsdWriter} produces (named complexTypes, each a flat xs:sequence of elements/
 * xs:choice, or xs:simpleContent) -- a hand-written XSD with imports/groups/xs:all is out of
 * scope for this best-effort comparison.
 */
final class BaselineDiff {

    record FieldInfo(String type, boolean required, boolean repeats) {
    }

    record Change(String path, String kind, String before, String after) {
    }

    private BaselineDiff() {
    }

    static java.util.List<Change> diff(ElementNode newRoot, Path baselineXsd) {
        Map<String, FieldInfo> before = flattenBaseline(baselineXsd);
        Map<String, FieldInfo> after = flattenNew(newRoot, "", 0, true);

        java.util.List<Change> changes = new java.util.ArrayList<>();
        for (var entry : after.entrySet()) {
            FieldInfo oldInfo = before.get(entry.getKey());
            if (oldInfo == null) {
                changes.add(new Change(entry.getKey(), "added", "-", describe(entry.getValue())));
                continue;
            }
            FieldInfo newInfo = entry.getValue();
            if (oldInfo.required() != newInfo.required()) {
                changes.add(new Change(entry.getKey(), "requiredness changed",
                        oldInfo.required() ? "required" : "optional", newInfo.required() ? "required" : "optional"));
            }
            if (!oldInfo.type().equals(newInfo.type())) {
                changes.add(new Change(entry.getKey(), "datatype changed", oldInfo.type(), newInfo.type()));
            }
            if (oldInfo.repeats() != newInfo.repeats()) {
                changes.add(new Change(entry.getKey(), "repeats changed", String.valueOf(oldInfo.repeats()), String.valueOf(newInfo.repeats())));
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                changes.add(new Change(path, "removed", describe(before.get(path)), "-"));
            }
        }
        return changes;
    }

    private static String describe(FieldInfo info) {
        return info.type() + (info.required() ? " (required)" : " (optional)") + (info.repeats() ? " (repeats)" : "");
    }

    private static Map<String, FieldInfo> flattenNew(ElementNode node, String path, int parentTotalInstances, boolean isRoot) {
        Map<String, FieldInfo> out = new LinkedHashMap<>();
        String name = path.isEmpty() ? node.qname.getLocalPart() : path + "/" + node.qname.getLocalPart();
        String type = node.hasChildren ? "complex" : DatatypeGuesser.guess(node.textSamples, node.qname.getLocalPart());
        boolean required = isRoot || node.isRequired(parentTotalInstances);
        out.put(name, new FieldInfo(type, required, node.repeats()));
        for (ElementNode child : node.children.values()) {
            out.putAll(flattenNew(child, name, node.totalInstances, false));
        }
        return out;
    }

    private static Map<String, FieldInfo> flattenBaseline(Path baselineXsd) {
        Map<String, FieldInfo> out = new LinkedHashMap<>();
        XmlSchemaCollection collection = new XmlSchemaCollection();
        XmlSchema schema;
        try (InputStream in = new FileInputStream(baselineXsd.toFile())) {
            schema = collection.read(new org.xml.sax.InputSource(in));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse baseline XSD: " + baselineXsd + " -- " + e.getMessage(), e);
        }
        if (schema.getElements().isEmpty()) {
            return out;
        }
        XmlSchemaElement root = schema.getElements().values().iterator().next();
        walkBaseline(collection, root, "", out, true);
        return out;
    }

    private static void walkBaseline(XmlSchemaCollection collection, XmlSchemaElement element, String path,
                                      Map<String, FieldInfo> out, boolean isRoot) {
        String name = path.isEmpty() ? element.getName() : path + "/" + element.getName();
        boolean required = isRoot || element.getMinOccurs() >= 1;
        boolean repeats = element.getMaxOccurs() > 1;

        XmlSchemaType type = element.getSchemaType();
        if (type == null && element.getSchemaTypeName() != null) {
            type = collection.getTypeByQName(element.getSchemaTypeName());
        }
        if (type instanceof XmlSchemaComplexType complexType) {
            out.put(name, new FieldInfo("complex", required, repeats));
            if (complexType.getParticle() instanceof XmlSchemaSequence seq) {
                for (XmlSchemaSequenceMember member : seq.getItems()) {
                    walkBaselineMember(collection, member, name, out);
                }
            } else if (complexType.getContentModel() instanceof XmlSchemaSimpleContent) {
                // Leaf with attributes only; already recorded as "complex" above -- good enough
                // for this diff's purposes (attribute-level diffing is out of scope).
            }
        } else {
            String typeName = element.getSchemaTypeName() != null ? element.getSchemaTypeName().getLocalPart() : "string";
            out.put(name, new FieldInfo(typeName, required, repeats));
        }
    }

    private static void walkBaselineMember(XmlSchemaCollection collection, Object member, String path, Map<String, FieldInfo> out) {
        if (member instanceof XmlSchemaElement el) {
            walkBaseline(collection, el, path, out, false);
        } else if (member instanceof XmlSchemaChoice choice) {
            for (Object item : choice.getItems()) {
                walkBaselineMember(collection, item, path, out);
            }
        }
    }
}
