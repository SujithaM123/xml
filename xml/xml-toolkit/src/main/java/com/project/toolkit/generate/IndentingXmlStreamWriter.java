package com.project.toolkit.generate;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Wraps a StAX writer to add a newline and indentation before each structural (element) start/end
 * tag, while leaving a leaf element's own text content exactly as written -- {@code <tag>value</tag>}
 * stays on one line, with nothing inserted inside it. Since XML Schema element-only content models
 * always treat inter-element whitespace as ignorable, this is purely cosmetic: it never changes a
 * value that gets validated or compared.
 */
final class IndentingXmlStreamWriter implements XMLStreamWriter {

    private static final String INDENT_UNIT = "  ";

    private final XMLStreamWriter out;
    private final Deque<Boolean> hasChildElement = new ArrayDeque<>();
    private int depth = 0;

    IndentingXmlStreamWriter(XMLStreamWriter out) {
        this.out = out;
    }

    private void markParentHasChild() {
        if (!hasChildElement.isEmpty()) {
            hasChildElement.pop();
            hasChildElement.push(true);
        }
    }

    private void newLine() throws XMLStreamException {
        out.writeCharacters("\n" + INDENT_UNIT.repeat(depth));
    }

    @Override
    public void writeStartElement(String localName) throws XMLStreamException {
        markParentHasChild();
        newLine();
        out.writeStartElement(localName);
        hasChildElement.push(false);
        depth++;
    }

    @Override
    public void writeStartElement(String namespaceURI, String localName) throws XMLStreamException {
        markParentHasChild();
        newLine();
        out.writeStartElement(namespaceURI, localName);
        hasChildElement.push(false);
        depth++;
    }

    @Override
    public void writeStartElement(String prefix, String localName, String namespaceURI) throws XMLStreamException {
        markParentHasChild();
        newLine();
        out.writeStartElement(prefix, localName, namespaceURI);
        hasChildElement.push(false);
        depth++;
    }

    @Override
    public void writeEmptyElement(String namespaceURI, String localName) throws XMLStreamException {
        markParentHasChild();
        newLine();
        out.writeEmptyElement(namespaceURI, localName);
    }

    @Override
    public void writeEmptyElement(String prefix, String localName, String namespaceURI) throws XMLStreamException {
        markParentHasChild();
        newLine();
        out.writeEmptyElement(prefix, localName, namespaceURI);
    }

    @Override
    public void writeEmptyElement(String localName) throws XMLStreamException {
        markParentHasChild();
        newLine();
        out.writeEmptyElement(localName);
    }

    @Override
    public void writeEndElement() throws XMLStreamException {
        depth--;
        boolean hadChild = hasChildElement.pop();
        if (hadChild) {
            newLine();
        }
        out.writeEndElement();
    }

    @Override
    public void writeEndDocument() throws XMLStreamException {
        out.writeEndDocument();
    }

    @Override
    public void close() throws XMLStreamException {
        out.close();
    }

    @Override
    public void flush() throws XMLStreamException {
        out.flush();
    }

    @Override
    public void writeAttribute(String localName, String value) throws XMLStreamException {
        out.writeAttribute(localName, value);
    }

    @Override
    public void writeAttribute(String prefix, String namespaceURI, String localName, String value) throws XMLStreamException {
        out.writeAttribute(prefix, namespaceURI, localName, value);
    }

    @Override
    public void writeAttribute(String namespaceURI, String localName, String value) throws XMLStreamException {
        out.writeAttribute(namespaceURI, localName, value);
    }

    @Override
    public void writeNamespace(String prefix, String namespaceURI) throws XMLStreamException {
        out.writeNamespace(prefix, namespaceURI);
    }

    @Override
    public void writeDefaultNamespace(String namespaceURI) throws XMLStreamException {
        out.writeDefaultNamespace(namespaceURI);
    }

    @Override
    public void writeComment(String data) throws XMLStreamException {
        out.writeComment(data);
    }

    @Override
    public void writeProcessingInstruction(String target) throws XMLStreamException {
        out.writeProcessingInstruction(target);
    }

    @Override
    public void writeProcessingInstruction(String target, String data) throws XMLStreamException {
        out.writeProcessingInstruction(target, data);
    }

    @Override
    public void writeCData(String data) throws XMLStreamException {
        out.writeCData(data);
    }

    @Override
    public void writeDTD(String dtd) throws XMLStreamException {
        out.writeDTD(dtd);
    }

    @Override
    public void writeEntityRef(String name) throws XMLStreamException {
        out.writeEntityRef(name);
    }

    @Override
    public void writeStartDocument() throws XMLStreamException {
        out.writeStartDocument();
    }

    @Override
    public void writeStartDocument(String version) throws XMLStreamException {
        out.writeStartDocument(version);
    }

    @Override
    public void writeStartDocument(String encoding, String version) throws XMLStreamException {
        out.writeStartDocument(encoding, version);
    }

    @Override
    public void writeCharacters(String text) throws XMLStreamException {
        out.writeCharacters(text);
    }

    @Override
    public void writeCharacters(char[] text, int start, int len) throws XMLStreamException {
        out.writeCharacters(text, start, len);
    }

    @Override
    public String getPrefix(String uri) throws XMLStreamException {
        return out.getPrefix(uri);
    }

    @Override
    public void setPrefix(String prefix, String uri) throws XMLStreamException {
        out.setPrefix(prefix, uri);
    }

    @Override
    public void setDefaultNamespace(String uri) throws XMLStreamException {
        out.setDefaultNamespace(uri);
    }

    @Override
    public void setNamespaceContext(NamespaceContext context) throws XMLStreamException {
        out.setNamespaceContext(context);
    }

    @Override
    public NamespaceContext getNamespaceContext() {
        return out.getNamespaceContext();
    }

    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        return out.getProperty(name);
    }
}
