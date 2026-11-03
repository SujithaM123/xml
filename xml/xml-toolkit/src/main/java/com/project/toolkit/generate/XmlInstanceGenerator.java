package com.project.toolkit.generate;

import org.apache.ws.commons.schema.*;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Walks an XSD element's type tree (sequence/choice/all, attributes, simple content) and
 * streams out one matching XML instance via StAX. Required elements/attributes are always
 * written; optional ones are included about 70% of the time; maxOccurs&gt;1 elements are
 * repeated according to the requested {@link SizeTier}, damped by nesting depth so repeats
 * inside repeats don't explode combinatorially.
 */
final class XmlInstanceGenerator {

    private static final double OPTIONAL_INCLUDE_PROBABILITY = 0.70;
    private static final int MAX_DEPTH = 25;
    private static final String NS_PREFIX = "ns";
    private static final String EXT_PREFIX = "ext";
    private static final Set<String> NON_STRING_PRIMITIVES = Set.of(
            "integer", "int", "long", "short", "nonNegativeInteger", "positiveInteger",
            "decimal", "date", "dateTime", "boolean", "gYear", "gYearMonth");
    private static final Set<String> DATE_LIKE_PRIMITIVES = Set.of("date", "dateTime", "gYear", "gYearMonth");
    private static final String INVALID_NAMESPACE_PREFIX = "bad";
    private static final String INVALID_NAMESPACE_URI = "urn:toolkit:invalid-namespace";

    private final SchemaIndex index;
    private final SimpleTypeResolver typeResolver;
    private final ValueGenerator valueGenerator;
    private final int repeatFactor;
    private final Random rnd;
    private final ContentLevel level;
    private final ErrorInjectionContext injection;
    private final List<String> pathStack = new ArrayList<>();
    private final boolean embedSupplementPlaceholder;
    private int anySlotCounter;

    /**
     * @param repeatFactor how many times each maxOccurs&gt;1 element repeats, applied the same
     *                     at every nesting depth. Nested unbounded lists compound multiplicatively
     *                     (factor^N for N nested levels), which is what actually drives output
     *                     size -- calibrated once per XSD+tier by {@link GenerateCommand}. Only
     *                     used at {@link ContentLevel#FULL}; the other levels ignore it.
     * @param injection    null for an ordinary (valid) document; otherwise drives the one-point
     *                     error injection described on {@link ErrorInjectionContext}.
     * @param embedSupplementPlaceholder when true, every xs:any slot gets a uniquely-indexed
     *                     {@code <SupplementPlaceholder idx="N">} marker instead of the ordinary
     *                     filler, for {@link GenerateCommand} to replace with a real, independently
     *                     generated-and-validated supplement document afterward (A7). False keeps
     *                     the original fixed filler unchanged.
     */
    XmlInstanceGenerator(SchemaIndex index, int repeatFactor, Random rnd, NameValueProfile profile, ContentLevel level,
                          ErrorInjectionContext injection, boolean embedSupplementPlaceholder) {
        this.index = index;
        this.typeResolver = new SimpleTypeResolver(index.collection);
        this.valueGenerator = new ValueGenerator(rnd, profile, index.allElementNames(), index.rootElement.getName());
        this.repeatFactor = repeatFactor;
        this.rnd = rnd;
        this.level = level;
        this.injection = injection;
        this.embedSupplementPlaceholder = embedSupplementPlaceholder;
    }

    void writeDocument(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartDocument("UTF-8", "1.0");
        writeElement(writer, index.rootElement, 0, true);
        writer.writeEndDocument();
    }

    private void writeElement(XMLStreamWriter writer, XmlSchemaElement element, int depth) throws XMLStreamException {
        writeElement(writer, element, depth, false);
    }

    /**
     * Uses a fixed prefix bound once on the root element rather than the StAX writer's
     * "repairing namespaces" auto-prefix, whose internal counter isn't seeded by us and would
     * otherwise make two runs with the same seed emit different (if equally valid) prefixes.
     */
    private void writeElement(XMLStreamWriter writer, XmlSchemaElement element, int depth, boolean declareNamespace) throws XMLStreamException {
        // A local particle declared with ref="..." carries no name/type of its own -- only the
        // global element it points to does. minOccurs/maxOccurs stay on the referencing particle
        // itself (handled by the caller, writeElementWithOccurs, before this is ever reached), but
        // everything about what gets WRITTEN here -- name, type, attributes, children -- has to
        // come from the resolved target, or this element silently (or, for its name, not so
        // silently -- a null localName crashes the writer) has nothing to write.
        XmlSchemaElement effective = resolveRef(element);
        pathStack.add(effective.getName());
        try {
            String mainNamespace = index.schema.getTargetNamespace();
            // A ref target is always a global element, so its own QName's namespace is exactly
            // right (global elements are implicitly qualified regardless of elementFormDefault) --
            // and it can genuinely differ from this document's main namespace when the ref crosses
            // an xs:import. Every other element keeps using the main schema's namespace, unchanged.
            boolean crossNamespace = element.isRef() && effective.getQName() != null
                    && !effective.getQName().getNamespaceURI().equals(mainNamespace);
            String namespace = crossNamespace ? effective.getQName().getNamespaceURI() : mainNamespace;

            // Only ever applied to a non-root element: swapping the root's own namespace would
            // also break other code that looks for the document's real root tag verbatim (e.g.
            // supplement embedding). A fresh prefix bound right here, rather than reusing NS_PREFIX
            // with a different URI, is what actually changes the serialized element's namespace --
            // repairNamespaces is off, so writeStartElement's URI argument alone doesn't do that.
            if (!declareNamespace && injection != null && injection.isEligible("invalid-namespace")
                    && injection.shouldInjectThisOccurrence()) {
                injection.recordApplied(currentPath(), namespace, INVALID_NAMESPACE_URI);
                writer.writeStartElement(INVALID_NAMESPACE_PREFIX, effective.getName(), INVALID_NAMESPACE_URI);
                writer.writeNamespace(INVALID_NAMESPACE_PREFIX, INVALID_NAMESPACE_URI);
            } else if (crossNamespace) {
                // Same reasoning as above: a prefix bound on this element's own start tag is what
                // actually puts it in the right namespace, not the writeStartElement URI argument.
                writer.writeStartElement(EXT_PREFIX, effective.getName(), namespace);
                writer.writeNamespace(EXT_PREFIX, namespace);
            } else {
                writer.writeStartElement(NS_PREFIX, effective.getName(), namespace);
                if (declareNamespace) {
                    writer.writeNamespace(NS_PREFIX, namespace);
                }
            }

            QName namedType = effective.getSchemaTypeName();
            if (namedType != null && org.apache.ws.commons.schema.constants.Constants.URI_2001_SCHEMA_XSD.equals(namedType.getNamespaceURI())) {
                writer.writeCharacters(leafValue(effective.getName(), typeResolver.resolve(namedType)));
                writer.writeEndElement();
                return;
            }

            XmlSchemaType type = resolveElementType(effective);
            if (type instanceof XmlSchemaComplexType complexType) {
                writeComplexContent(writer, complexType, effective.getName(), depth);
            } else if (type instanceof XmlSchemaSimpleType simpleType) {
                QName typeName = namedType != null ? namedType : simpleType.getQName();
                ResolvedSimpleType resolved = typeResolver.resolve(typeName);
                writer.writeCharacters(leafValue(effective.getName(), resolved));
            } else {
                // Untyped/unresolvable element: write a generic string so the document stays well-formed.
                writer.writeCharacters("VALUE");
            }
            writer.writeEndElement();
        } finally {
            pathStack.remove(pathStack.size() - 1);
        }
    }

    /** Resolves an element="ref" particle to the global element it points to; returns the
     * particle itself unchanged when it isn't a ref (the overwhelmingly common case), or when a
     * ref's target couldn't be resolved (e.g. the referenced namespace's schema failed to load) --
     * in the latter case the caller's existing "untyped/unresolvable" fallback still applies. */
    private XmlSchemaElement resolveRef(XmlSchemaElement element) {
        if (element.isRef()) {
            XmlSchemaElement target = element.getRef().getTarget();
            if (target != null) {
                return target;
            }
        }
        return element;
    }

    private String currentPath() {
        return String.join("/", pathStack);
    }

    /** Applies the chosen error type at most once per document, at a leaf value, when this leaf
     * matches that type's precondition (e.g. invalid-code needs an enumeration). See
     * {@link ErrorInjectionContext}. */
    private String leafValue(String elementName, ResolvedSimpleType resolved) {
        String normal = valueGenerator.generate(elementName, resolved);
        if (injection == null) {
            return normal;
        }
        String path = currentPath();
        if (injection.isEligible("invalid-code") && !resolved.enumerationValues.isEmpty()) {
            if (injection.shouldInjectThisOccurrence()) {
                String bad = "ZZZ-NOT-A-VALID-CODE";
                injection.recordApplied(path, normal, bad);
                return bad;
            }
        } else if (injection.isEligible("pattern-break") && resolved.pattern != null) {
            if (injection.shouldInjectThisOccurrence()) {
                String bad = "###BREAKS-PATTERN###";
                injection.recordApplied(path, normal, bad);
                return bad;
            }
        } else if (injection.isEligible("too-long") && resolved.maxLength != null) {
            if (injection.shouldInjectThisOccurrence()) {
                String bad = normal + "X".repeat(resolved.maxLength + 10);
                injection.recordApplied(path, normal, bad);
                return bad;
            }
        } else if (injection.isEligible("wrong-datatype") && isNonStringPrimitive(resolved)) {
            if (injection.shouldInjectThisOccurrence()) {
                String bad = "NOT-A-VALID-" + resolved.primitive.getLocalPart().toUpperCase();
                injection.recordApplied(path, normal, bad);
                return bad;
            }
        } else if (injection.isEligible("invalid-date") && isDateLike(resolved)) {
            if (injection.shouldInjectThisOccurrence()) {
                // Not just non-date garbage (that's "wrong-datatype") -- an out-of-range
                // month/day, so the failure is a format/range violation rather than "not a
                // date-shaped value at all".
                String bad = "9999-13-45";
                injection.recordApplied(path, normal, bad);
                return bad;
            }
        }
        // "invalid-characters" and "malformed-xml" are NOT handled here: the StAX writer silently
        // strips characters the XML spec disallows (verified empirically) rather than passing
        // them through, so both are instead applied as a post-serialization text edit in
        // GenerateCommand, after this generator has produced a normal, valid document.
        return normal;
    }

    private boolean isNonStringPrimitive(ResolvedSimpleType resolved) {
        return resolved.enumerationValues.isEmpty() && NON_STRING_PRIMITIVES.contains(resolved.primitive.getLocalPart());
    }

    private boolean isDateLike(ResolvedSimpleType resolved) {
        return resolved.enumerationValues.isEmpty() && DATE_LIKE_PRIMITIVES.contains(resolved.primitive.getLocalPart());
    }

    /** Whatever facet-based corruption applies to this type (enum/pattern/maxLength/non-string
     * primitive), or null if nothing about it can be deterministically made invalid -- shared
     * between element leaf values (via the branches above) and attribute values, so "invalid
     * datatype/value" means the same kind of thing in both places. */
    private String corruptionFor(ResolvedSimpleType resolved, String normal) {
        if (!resolved.enumerationValues.isEmpty()) {
            return "ZZZ-NOT-A-VALID-CODE";
        }
        if (resolved.pattern != null) {
            return "###BREAKS-PATTERN###";
        }
        if (resolved.maxLength != null) {
            return normal + "X".repeat(resolved.maxLength + 10);
        }
        if (isNonStringPrimitive(resolved)) {
            return "NOT-A-VALID-" + resolved.primitive.getLocalPart().toUpperCase();
        }
        return null;
    }

    private XmlSchemaType resolveElementType(XmlSchemaElement element) {
        if (element.getSchemaTypeName() != null) {
            XmlSchemaType byName = index.collection.getTypeByQName(element.getSchemaTypeName());
            if (byName != null) {
                return byName;
            }
            return builtinSimpleType(element.getSchemaTypeName());
        }
        return element.getSchemaType();
    }

    private XmlSchemaType builtinSimpleType(QName typeName) {
        // Built-in XSD primitives (e.g. xs:string) aren't registered as named types in the
        // collection; ResolvedSimpleType handles them directly, so a lightweight stand-in works.
        return index.collection.getTypeByQName(typeName);
    }

    private void writeComplexContent(XMLStreamWriter writer, XmlSchemaComplexType complexType, String elementName, int depth) throws XMLStreamException {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (complexType.getContentModel() instanceof XmlSchemaSimpleContent simpleContent
                && simpleContent.getContent() instanceof XmlSchemaSimpleContentExtension extension) {
            writeAttributes(writer, extension.getAttributes());
            maybeInjectUnexpectedElement(writer);
            ResolvedSimpleType resolved = typeResolver.resolve(extension.getBaseTypeName());
            writer.writeCharacters(leafValue(elementName, resolved));
            return;
        }

        // <xs:complexContent><xs:extension base="..."> / <xs:restriction base="..."> -- very
        // common for type inheritance/reuse -- keeps its own attributes/particle inside the
        // extension/restriction object, NOT on complexType directly. complexType.getAttributes()
        // and complexType.getParticle() are simply null for these, so without this branch every
        // element of such a type silently writes as empty (no exception, nothing invalid-looking
        // to catch in testing -- just quietly wrong).
        if (complexType.getContentModel() instanceof XmlSchemaComplexContent complexContent) {
            writeComplexContentModel(writer, complexContent, depth);
            return;
        }

        if (complexType.getAttributes() != null) {
            writeAttributes(writer, complexType.getAttributes());
        }
        maybeInjectUnexpectedElement(writer);
        XmlSchemaParticle particle = complexType.getParticle();
        if (particle != null) {
            writeParticle(writer, particle, depth);
        }
    }

    private void writeComplexContentModel(XMLStreamWriter writer, XmlSchemaComplexContent complexContent, int depth) throws XMLStreamException {
        if (complexContent.getContent() instanceof XmlSchemaComplexContentExtension extension) {
            // Extension APPENDS to the base type's own content, so the base's fields are written
            // first, in order, chasing further ancestors first if the base is itself derived.
            XmlSchemaComplexType base = resolveNamedComplexType(extension.getBaseTypeName());
            if (base != null) {
                writeInheritedContent(writer, base, depth);
            }
            writeAttributes(writer, extension.getAttributes());
            maybeInjectUnexpectedElement(writer);
            if (extension.getParticle() != null) {
                writeParticle(writer, extension.getParticle(), depth);
            }
        } else if (complexContent.getContent() instanceof XmlSchemaComplexContentRestriction restriction) {
            // A restriction redefines the content model from scratch -- the base's own
            // attributes/particle are deliberately NOT also written here, only what the
            // restriction itself redeclares.
            writeAttributes(writer, restriction.getAttributes());
            maybeInjectUnexpectedElement(writer);
            if (restriction.getParticle() != null) {
                writeParticle(writer, restriction.getParticle(), depth);
            }
        }
    }

    /** Writes a base type's own attributes/particle for an extension chain, recursing into
     * further ancestors first so a multi-level chain comes out oldest-first. No manifest/injection
     * side effects here -- those stay with the type actually being extended, in
     * {@link #writeComplexContentModel}, so a document gets at most one unexpected-element/
     * namespace-swap point per element regardless of how deep its type hierarchy is. */
    private void writeInheritedContent(XMLStreamWriter writer, XmlSchemaComplexType base, int depth) throws XMLStreamException {
        if (base.getContentModel() instanceof XmlSchemaComplexContent baseComplexContent) {
            if (baseComplexContent.getContent() instanceof XmlSchemaComplexContentExtension baseExtension) {
                XmlSchemaComplexType grandBase = resolveNamedComplexType(baseExtension.getBaseTypeName());
                if (grandBase != null) {
                    writeInheritedContent(writer, grandBase, depth);
                }
                writeAttributes(writer, baseExtension.getAttributes());
                if (baseExtension.getParticle() != null) {
                    writeParticle(writer, baseExtension.getParticle(), depth);
                }
            } else if (baseComplexContent.getContent() instanceof XmlSchemaComplexContentRestriction baseRestriction) {
                writeAttributes(writer, baseRestriction.getAttributes());
                if (baseRestriction.getParticle() != null) {
                    writeParticle(writer, baseRestriction.getParticle(), depth);
                }
            }
            return;
        }
        if (base.getAttributes() != null) {
            writeAttributes(writer, base.getAttributes());
        }
        if (base.getParticle() != null) {
            writeParticle(writer, base.getParticle(), depth);
        }
    }

    private XmlSchemaComplexType resolveNamedComplexType(QName typeName) {
        if (typeName != null && index.collection.getTypeByQName(typeName) instanceof XmlSchemaComplexType ct) {
            return ct;
        }
        return null;
    }

    /** Inserts one element the schema never declared, right where this complex element's real
     * content is about to start -- "invalid content was found starting with element ..." is a
     * reliable, universal failure for any complex element, so this needs no facet precondition. */
    private void maybeInjectUnexpectedElement(XMLStreamWriter writer) throws XMLStreamException {
        if (injection != null && injection.isEligible("unexpected-element") && injection.shouldInjectThisOccurrence()) {
            injection.recordApplied(currentPath(), "(normal content)", "(extra <UnexpectedElement> inserted)");
            String namespace = index.schema.getTargetNamespace();
            writer.writeStartElement(NS_PREFIX, "UnexpectedElement", namespace);
            writer.writeCharacters("unexpected-content");
            writer.writeEndElement();
        }
    }

    private void writeAttributes(XMLStreamWriter writer, List<XmlSchemaAttributeOrGroupRef> attributes) throws XMLStreamException {
        if (attributes == null) {
            return;
        }
        for (XmlSchemaAttributeOrGroupRef item : attributes) {
            if (!(item instanceof XmlSchemaAttribute attr)) {
                continue;
            }
            boolean required = attr.getUse() == XmlSchemaUse.REQUIRED;
            if (injection != null && injection.isEligible("missing-attribute") && required
                    && injection.shouldInjectThisOccurrence()) {
                injection.recordApplied(currentPath() + "/@" + attr.getName(), "(attribute present)", "(attribute omitted)");
                continue;
            }
            if (!required && !includeOptional()) {
                continue;
            }
            ResolvedSimpleType resolved = attr.getSchemaTypeName() != null
                    ? typeResolver.resolve(attr.getSchemaTypeName())
                    : new ResolvedSimpleType();
            String normal = valueGenerator.generate(attr.getName(), resolved);
            String value = normal;
            if (injection != null && injection.isEligible("invalid-attribute-value")) {
                String bad = corruptionFor(resolved, normal);
                if (bad != null && injection.shouldInjectThisOccurrence()) {
                    injection.recordApplied(currentPath() + "/@" + attr.getName(), normal, bad);
                    value = bad;
                }
            }
            writer.writeAttribute(attr.getName(), value);
        }
    }

    private void writeParticle(XMLStreamWriter writer, XmlSchemaParticle particle, int depth) throws XMLStreamException {
        if (particle instanceof XmlSchemaSequence sequence) {
            for (XmlSchemaSequenceMember member : sequence.getItems()) {
                writeParticleMember(writer, member, depth);
            }
        } else if (particle instanceof XmlSchemaAll all) {
            for (XmlSchemaAllMember member : all.getItems()) {
                writeParticleMember(writer, member, depth);
            }
        } else if (particle instanceof XmlSchemaChoice choice) {
            writeChoice(writer, choice, depth);
        } else if (particle instanceof XmlSchemaGroupRef groupRef) {
            XmlSchemaParticle resolved = groupRef.getParticle();
            if (resolved != null) {
                writeParticle(writer, resolved, depth);
            }
        } else if (particle instanceof XmlSchemaAny) {
            writeAnyFiller(writer);
        }
    }

    private void writeParticleMember(XMLStreamWriter writer, Object member, int depth) throws XMLStreamException {
        if (member instanceof XmlSchemaElement element) {
            writeElementWithOccurs(writer, element, depth);
        } else if (member instanceof XmlSchemaParticle particle) {
            writeParticle(writer, particle, depth);
        }
    }

    private void writeChoice(XMLStreamWriter writer, XmlSchemaChoice choice, int depth) throws XMLStreamException {
        List<XmlSchemaChoiceMember> items = choice.getItems();
        if (items.isEmpty()) {
            return;
        }
        boolean required = choice.getMinOccurs() >= 1;
        if (!required && !includeOptional()) {
            return;
        }
        int repeatCount = repeatCountFor(choice.getMinOccurs(), choice.getMaxOccurs());
        for (int i = 0; i < repeatCount; i++) {
            Object picked = items.get(rnd.nextInt(items.size()));
            writeParticleMember(writer, picked, depth);
        }
    }

    private void writeAnyFiller(XMLStreamWriter writer) throws XMLStreamException {
        if (embedSupplementPlaceholder) {
            writer.writeStartElement("SupplementPlaceholder");
            writer.writeAttribute("idx", String.valueOf(anySlotCounter++));
            writer.writeEndElement();
            return;
        }
        writer.writeStartElement("ExtensionData");
        writer.writeCharacters("supplementary-data");
        writer.writeEndElement();
    }

    private void writeElementWithOccurs(XMLStreamWriter writer, XmlSchemaElement element, int depth) throws XMLStreamException {
        // minOccurs/maxOccurs belong to this particle itself even when it's a ref -- only the
        // name used for logging needs the resolved target (writeElement resolves the ref again,
        // itself, for everything it actually writes).
        boolean required = element.getMinOccurs() >= 1;
        if (injection != null && injection.isEligible("missing-required") && required
                && injection.shouldInjectThisOccurrence()) {
            injection.recordApplied(currentPath() + "/" + resolveRef(element).getName(), "(element present)", "(element omitted)");
            return;
        }
        if (!required && !includeOptional()) {
            return;
        }
        int repeatCount = repeatCountFor(element.getMinOccurs(), element.getMaxOccurs());
        long maxOccurs = element.getMaxOccurs();
        if (injection != null && injection.isEligible("too-many-repeats") && maxOccurs > 1 && maxOccurs != Long.MAX_VALUE
                && injection.shouldInjectThisOccurrence()) {
            int bad = (int) maxOccurs + 1;
            injection.recordApplied(currentPath() + "/" + resolveRef(element).getName(), "maxOccurs=" + maxOccurs, "wrote " + bad + " occurrences");
            repeatCount = bad;
        }
        for (int i = 0; i < repeatCount; i++) {
            writeElement(writer, element, depth + 1);
        }
    }

    /** Level 3 keeps the original probabilistic inclusion; level 1 never includes an optional
     * item, level 2 always does -- both so "required only" and "required + optional" are exact,
     * reproducible content sets rather than another random sample. */
    private boolean includeOptional() {
        return switch (level) {
            case REQUIRED_ONLY -> false;
            case REQUIRED_AND_OPTIONAL -> true;
            case FULL -> rnd.nextDouble() <= OPTIONAL_INCLUDE_PROBABILITY;
        };
    }

    private int repeatCountFor(long minOccurs, long maxOccurs) {
        if (level != ContentLevel.FULL) {
            // "1 occurrence of repeating elements" at levels 1 and 2 -- --size only applies at
            // level 3, so there's no repeatFactor to honor here.
            return (int) Math.max(1, minOccurs);
        }
        if (maxOccurs <= 1) {
            return (int) Math.max(1, minOccurs);
        }
        long cap = maxOccurs == Long.MAX_VALUE ? Integer.MAX_VALUE : maxOccurs;
        long count = Math.max(Math.max(1, minOccurs), repeatFactor);
        return (int) Math.min(cap, count);
    }
}
