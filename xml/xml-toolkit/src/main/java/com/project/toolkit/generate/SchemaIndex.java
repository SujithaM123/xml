package com.project.toolkit.generate;

import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaElement;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/** Loads one XSD -- including whatever it pulls in via xs:import/xs:include, resolved relative
 * to the XSD's own folder -- and finds its single top-level element. */
final class SchemaIndex {

    final XmlSchemaCollection collection;
    final XmlSchema schema;
    final XmlSchemaElement rootElement;
    private java.util.List<String> elementNamesCache;

    SchemaIndex(Path xsdPath) {
        this.collection = new XmlSchemaCollection();
        // Without a systemId, a relative xs:import/xs:include schemaLocation resolves against
        // the JVM's working directory instead of the XSD's own folder -- wrong file (or none
        // found) for any multi-file schema. The absolute file URI fixes that.
        try (InputStream in = new FileInputStream(xsdPath.toFile())) {
            org.xml.sax.InputSource source = new org.xml.sax.InputSource(in);
            source.setSystemId(xsdPath.toAbsolutePath().normalize().toUri().toString());
            this.schema = collection.read(source);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse XSD: " + xsdPath + " -- " + e.getMessage(), e);
        }
        this.rootElement = findRootElement(xsdPath);
    }

    private XmlSchemaElement findRootElement(Path xsdPath) {
        Map<javax.xml.namespace.QName, XmlSchemaElement> elements = schema.getElements();
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("XSD has no top-level element: " + xsdPath);
        }
        for (XmlSchemaElement el : elements.values()) {
            if ("Document".equals(el.getName())) {
                return el;
            }
        }
        return elements.values().iterator().next();
    }

    /** Every element local name reachable from the root -- computed once, lazily, since it's the
     * same for every file/trial in a run and only needed when a name-value profile rule asks
     * for a path into the message (see {@link NameValueProfile.Rule#ELEMENT_PATH}). */
    java.util.List<String> allElementNames() {
        if (elementNamesCache == null) {
            elementNamesCache = SchemaElementNames.collect(this);
        }
        return elementNamesCache;
    }
}
