package com.project.toolkit.generate;

import org.apache.ws.commons.schema.*;
import org.apache.ws.commons.schema.constants.Constants;

import javax.xml.namespace.QName;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only walk of an XSD's element tree (mirrors {@link SchemaElementNames}'s traversal, but
 * collecting facet/structure counts instead of names) -- the basis for A8's schema-scan output
 * and for A6's "only offer error types this XSD actually supports".
 */
public final class SchemaScanner {

    private static final int MAX_DEPTH = 30;
    private static final Set<String> NON_STRING_PRIMITIVES = Set.of(
            "integer", "int", "long", "short", "nonNegativeInteger", "positiveInteger",
            "decimal", "date", "dateTime", "boolean", "gYear", "gYearMonth");
    private static final Set<String> DATE_LIKE_PRIMITIVES = Set.of("date", "dateTime", "gYear", "gYearMonth");

    private SchemaScanner() {
    }

    public static SchemaScanResult scan(SchemaIndex index) {
        SchemaScanResult r = new SchemaScanResult();
        SimpleTypeResolver resolver = new SimpleTypeResolver(index.collection);
        walkElement(index, index.rootElement, resolver, r, new HashSet<>(), 0, true);

        r.possibleLevels.add(1);
        r.possibleLevels.add(2);
        r.possibleLevels.add(3);

        r.possibleErrorTypes.add("malformed-xml");
        r.possibleErrorTypes.add("invalid-characters");
        r.possibleErrorTypes.add("wrong-datatype");
        // Both always apply to any complex-typed document: a foreign element is always
        // "invalid content", and there's always at least one non-root element whose namespace
        // can be swapped out.
        r.possibleErrorTypes.add("unexpected-element");
        if (r.requiredCount > 0 || r.optionalCount > 0) {
            r.possibleErrorTypes.add("invalid-namespace");
        }
        if (r.requiredCount > 0) {
            r.possibleErrorTypes.add("missing-required");
        }
        if (r.enumerationCount > 0) {
            r.possibleErrorTypes.add("invalid-code");
        }
        if (r.patternCount > 0) {
            r.possibleErrorTypes.add("pattern-break");
        }
        if (r.maxLengthCount > 0) {
            r.possibleErrorTypes.add("too-long");
        }
        if (r.finiteRepeatCount > 0) {
            r.possibleErrorTypes.add("too-many-repeats");
        }
        if (r.dateTypeCount > 0) {
            r.possibleErrorTypes.add("invalid-date");
        }
        if (r.requiredAttributeCount > 0) {
            r.possibleErrorTypes.add("missing-attribute");
        }
        if (r.corruptibleAttributeCount > 0) {
            r.possibleErrorTypes.add("invalid-attribute-value");
        }
        return r;
    }

    private static void walkElement(SchemaIndex index, XmlSchemaElement element, SimpleTypeResolver resolver,
                                      SchemaScanResult r, Set<QName> visitedTypes, int depth, boolean isRoot) {
        r.maxDepth = Math.max(r.maxDepth, depth);
        if (!isRoot) {
            if (element.getMinOccurs() >= 1) {
                r.requiredCount++;
            } else {
                r.optionalCount++;
            }
            long max = element.getMaxOccurs();
            if (max > 1) {
                r.repeatingCount++;
                if (max != Long.MAX_VALUE) {
                    r.finiteRepeatCount++;
                }
            }
        }
        if (depth > MAX_DEPTH) {
            return;
        }

        QName namedType = element.getSchemaTypeName();
        if (namedType != null && Constants.URI_2001_SCHEMA_XSD.equals(namedType.getNamespaceURI())) {
            if (DATE_LIKE_PRIMITIVES.contains(namedType.getLocalPart())) {
                r.dateTypeCount++;
            }
            return; // built-in primitive leaf, nothing further to scan
        }

        XmlSchemaType type = resolveType(index, element);
        if (type instanceof XmlSchemaSimpleType simpleType) {
            noteFacets(resolver, namedType != null ? namedType : simpleType.getQName(), r);
            return;
        }
        if (!(type instanceof XmlSchemaComplexType complexType)) {
            return;
        }
        if (namedType != null && !visitedTypes.add(namedType)) {
            return;
        }

        if (complexType.getContentModel() instanceof XmlSchemaSimpleContent simpleContent
                && simpleContent.getContent() instanceof XmlSchemaSimpleContentExtension extension) {
            walkAttributes(resolver, extension.getAttributes(), r);
            noteFacets(resolver, extension.getBaseTypeName(), r);
            return;
        }
        walkAttributes(resolver, complexType.getAttributes(), r);
        if (complexType.getParticle() != null) {
            walkParticle(index, complexType.getParticle(), resolver, r, visitedTypes, depth + 1);
        }
    }

    private static void walkAttributes(SimpleTypeResolver resolver, List<XmlSchemaAttributeOrGroupRef> attributes, SchemaScanResult r) {
        if (attributes == null) {
            return;
        }
        for (XmlSchemaAttributeOrGroupRef item : attributes) {
            if (!(item instanceof XmlSchemaAttribute attr)) {
                continue;
            }
            r.attributeCount++;
            if (attr.getUse() == XmlSchemaUse.REQUIRED) {
                r.requiredAttributeCount++;
            }
            if (attr.getSchemaTypeName() != null) {
                ResolvedSimpleType resolved = resolver.resolve(attr.getSchemaTypeName());
                if (!resolved.enumerationValues.isEmpty() || resolved.pattern != null || resolved.maxLength != null
                        || (resolved.enumerationValues.isEmpty() && NON_STRING_PRIMITIVES.contains(resolved.primitive.getLocalPart()))) {
                    r.corruptibleAttributeCount++;
                }
            }
        }
    }

    private static void noteFacets(SimpleTypeResolver resolver, QName typeName, SchemaScanResult r) {
        ResolvedSimpleType resolved = resolver.resolve(typeName);
        if (!resolved.enumerationValues.isEmpty()) {
            r.enumerationCount++;
        }
        if (resolved.pattern != null) {
            r.patternCount++;
        }
        if (resolved.maxLength != null) {
            r.maxLengthCount++;
        }
        if (DATE_LIKE_PRIMITIVES.contains(resolved.primitive.getLocalPart())) {
            r.dateTypeCount++;
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

    private static void walkParticle(SchemaIndex index, XmlSchemaParticle particle, SimpleTypeResolver resolver,
                                      SchemaScanResult r, Set<QName> visitedTypes, int depth) {
        if (particle instanceof XmlSchemaSequence seq) {
            for (XmlSchemaSequenceMember m : seq.getItems()) {
                walkMember(index, m, resolver, r, visitedTypes, depth);
            }
        } else if (particle instanceof XmlSchemaChoice choice) {
            r.choiceCount++;
            for (Object m : choice.getItems()) {
                walkMember(index, m, resolver, r, visitedTypes, depth);
            }
        } else if (particle instanceof XmlSchemaAll all) {
            for (XmlSchemaAllMember m : all.getItems()) {
                walkMember(index, m, resolver, r, visitedTypes, depth);
            }
        } else if (particle instanceof XmlSchemaGroupRef groupRef && groupRef.getParticle() != null) {
            walkParticle(index, groupRef.getParticle(), resolver, r, visitedTypes, depth);
        } else if (particle instanceof XmlSchemaAny) {
            r.anySlotCount++;
        }
    }

    private static void walkMember(SchemaIndex index, Object member, SimpleTypeResolver resolver,
                                    SchemaScanResult r, Set<QName> visitedTypes, int depth) {
        if (member instanceof XmlSchemaElement el) {
            walkElement(index, el, resolver, r, visitedTypes, depth, false);
        } else if (member instanceof XmlSchemaParticle p) {
            walkParticle(index, p, resolver, r, visitedTypes, depth);
        }
    }
}
