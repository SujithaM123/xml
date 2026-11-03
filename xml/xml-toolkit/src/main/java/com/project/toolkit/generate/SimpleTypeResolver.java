package com.project.toolkit.generate;

import org.apache.ws.commons.schema.*;
import org.apache.ws.commons.schema.constants.Constants;

import javax.xml.namespace.QName;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks an XSD simple type's restriction chain up to its built-in base, merging facets
 * (enumeration/pattern/length/digits/min-max) as it goes. Child facets win over inherited ones,
 * matching how XSD restriction narrows a type.
 */
final class SimpleTypeResolver {

    private final XmlSchemaCollection collection;
    private final Map<QName, ResolvedSimpleType> cache = new HashMap<>();

    SimpleTypeResolver(XmlSchemaCollection collection) {
        this.collection = collection;
    }

    ResolvedSimpleType resolve(QName typeName) {
        return resolve(typeName, new HashSet<>());
    }

    private ResolvedSimpleType resolve(QName typeName, Set<QName> seen) {
        if (typeName == null) {
            return new ResolvedSimpleType();
        }
        ResolvedSimpleType cached = cache.get(typeName);
        if (cached != null) {
            return cached.copy();
        }
        if (Constants.URI_2001_SCHEMA_XSD.equals(typeName.getNamespaceURI())) {
            ResolvedSimpleType primitive = new ResolvedSimpleType();
            primitive.primitive = typeName;
            cache.put(typeName, primitive);
            return primitive.copy();
        }
        if (!seen.add(typeName)) {
            // Cyclic restriction chain (shouldn't happen in valid XSD); stop at string.
            return new ResolvedSimpleType();
        }

        XmlSchemaType type = collection.getTypeByQName(typeName);
        ResolvedSimpleType result;
        if (type instanceof XmlSchemaSimpleType simpleType
                && simpleType.getContent() instanceof XmlSchemaSimpleTypeRestriction restriction) {
            QName baseName = restriction.getBaseTypeName();
            ResolvedSimpleType base = resolve(baseName, seen);
            result = base.copy();
            applyFacets(result, restriction.getFacets());
        } else {
            result = new ResolvedSimpleType();
        }

        cache.put(typeName, result);
        return result.copy();
    }

    private void applyFacets(ResolvedSimpleType target, List<XmlSchemaFacet> facets) {
        if (facets == null) {
            return;
        }
        boolean enumerationSeenHere = false;
        for (XmlSchemaFacet obj : facets) {
            if (obj instanceof XmlSchemaEnumerationFacet f) {
                if (!enumerationSeenHere) {
                    target.enumerationValues.clear();
                    enumerationSeenHere = true;
                }
                target.enumerationValues.add(String.valueOf(f.getValue()));
            } else if (obj instanceof XmlSchemaPatternFacet f) {
                target.pattern = String.valueOf(f.getValue());
            } else if (obj instanceof XmlSchemaMinLengthFacet f) {
                target.minLength = Integer.parseInt(String.valueOf(f.getValue()));
            } else if (obj instanceof XmlSchemaMaxLengthFacet f) {
                target.maxLength = Integer.parseInt(String.valueOf(f.getValue()));
            } else if (obj instanceof XmlSchemaLengthFacet f) {
                target.length = Integer.parseInt(String.valueOf(f.getValue()));
            } else if (obj instanceof XmlSchemaTotalDigitsFacet f) {
                target.totalDigits = Integer.parseInt(String.valueOf(f.getValue()));
            } else if (obj instanceof XmlSchemaFractionDigitsFacet f) {
                target.fractionDigits = Integer.parseInt(String.valueOf(f.getValue()));
            } else if (obj instanceof XmlSchemaMinInclusiveFacet f) {
                target.minInclusive = String.valueOf(f.getValue());
            } else if (obj instanceof XmlSchemaMaxInclusiveFacet f) {
                target.maxInclusive = String.valueOf(f.getValue());
            } else if (obj instanceof XmlSchemaMinExclusiveFacet f) {
                target.minExclusive = String.valueOf(f.getValue());
            } else if (obj instanceof XmlSchemaMaxExclusiveFacet f) {
                target.maxExclusive = String.valueOf(f.getValue());
            }
        }
    }
}
