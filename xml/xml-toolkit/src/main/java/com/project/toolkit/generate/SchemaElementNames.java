package com.project.toolkit.generate;

import org.apache.ws.commons.schema.*;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects the local names of every element reachable from the root, for building plausible
 * "path to an element of the parent message" values (see {@link NameValueProfile}) -- read-only,
 * walked once per run, independent of {@link XmlInstanceGenerator}'s writing walk.
 */
final class SchemaElementNames {

    private static final int MAX_DEPTH = 6;

    private SchemaElementNames() {
    }

    static List<String> collect(SchemaIndex index) {
        Set<String> names = new LinkedHashSet<>();
        Set<QName> visitedTypes = new HashSet<>();
        collectFromElement(index, index.rootElement, names, visitedTypes, 0);
        return new ArrayList<>(names);
    }

    private static void collectFromElement(SchemaIndex index, XmlSchemaElement element, Set<String> names,
                                             Set<QName> visitedTypes, int depth) {
        names.add(element.getName());
        if (depth > MAX_DEPTH) {
            return;
        }
        XmlSchemaType type = resolveType(index, element);
        if (!(type instanceof XmlSchemaComplexType complexType)) {
            return;
        }
        QName typeName = element.getSchemaTypeName();
        if (typeName != null && !visitedTypes.add(typeName)) {
            return;
        }
        if (complexType.getParticle() != null) {
            collectFromParticle(index, complexType.getParticle(), names, visitedTypes, depth + 1);
        }
    }

    private static XmlSchemaType resolveType(SchemaIndex index, XmlSchemaElement element) {
        if (element.getSchemaTypeName() != null) {
            XmlSchemaType t = index.collection.getTypeByQName(element.getSchemaTypeName());
            if (t != null) {
                return t;
            }
        }
        return element.getSchemaType();
    }

    private static void collectFromParticle(SchemaIndex index, XmlSchemaParticle particle, Set<String> names,
                                              Set<QName> visitedTypes, int depth) {
        if (particle instanceof XmlSchemaSequence seq) {
            for (XmlSchemaSequenceMember m : seq.getItems()) {
                collectFromMember(index, m, names, visitedTypes, depth);
            }
        } else if (particle instanceof XmlSchemaChoice choice) {
            for (Object m : choice.getItems()) {
                collectFromMember(index, m, names, visitedTypes, depth);
            }
        } else if (particle instanceof XmlSchemaAll all) {
            for (XmlSchemaAllMember m : all.getItems()) {
                collectFromMember(index, m, names, visitedTypes, depth);
            }
        } else if (particle instanceof XmlSchemaGroupRef groupRef && groupRef.getParticle() != null) {
            collectFromParticle(index, groupRef.getParticle(), names, visitedTypes, depth);
        }
    }

    private static void collectFromMember(SchemaIndex index, Object member, Set<String> names,
                                           Set<QName> visitedTypes, int depth) {
        if (member instanceof XmlSchemaElement el) {
            collectFromElement(index, el, names, visitedTypes, depth);
        } else if (member instanceof XmlSchemaParticle p) {
            collectFromParticle(index, p, names, visitedTypes, depth);
        }
    }
}
