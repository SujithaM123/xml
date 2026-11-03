package com.project.toolkit.generate;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;

/**
 * The flattened result of walking an XSD simple type's restriction chain: the ultimate
 * built-in base type plus every facet collected along the way (most specific wins).
 */
final class ResolvedSimpleType {

    QName primitive = new QName("http://www.w3.org/2001/XMLSchema", "string");
    List<String> enumerationValues = new ArrayList<>();
    String pattern;
    Integer minLength;
    Integer maxLength;
    Integer length;
    Integer totalDigits;
    Integer fractionDigits;
    String minInclusive;
    String maxInclusive;
    String minExclusive;
    String maxExclusive;

    ResolvedSimpleType copy() {
        ResolvedSimpleType c = new ResolvedSimpleType();
        c.primitive = this.primitive;
        c.enumerationValues = new ArrayList<>(this.enumerationValues);
        c.pattern = this.pattern;
        c.minLength = this.minLength;
        c.maxLength = this.maxLength;
        c.length = this.length;
        c.totalDigits = this.totalDigits;
        c.fractionDigits = this.fractionDigits;
        c.minInclusive = this.minInclusive;
        c.maxInclusive = this.maxInclusive;
        c.minExclusive = this.minExclusive;
        c.maxExclusive = this.maxExclusive;
        return c;
    }
}
