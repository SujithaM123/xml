package com.project.toolkit.infer;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads one or more sample XMLs with StAX and merges them into a single tree of
 * {@link ElementNode}s, tracking per-occurrence presence so required/optional and
 * maxOccurs can be inferred across all samples at once.
 */
final class XmlStructureScanner {

    private ElementNode root;
    int fileCount;

    ElementNode scan(List<Path> xmlFiles) throws IOException {
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        inputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
        for (Path file : xmlFiles) {
            scanOne(file, inputFactory);
            fileCount++;
        }
        return root;
    }

    private void scanOne(Path file, XMLInputFactory inputFactory) throws IOException {
        Deque<Frame> stack = new ArrayDeque<>();
        Set<ElementNode> seenThisFile = new HashSet<>();
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = inputFactory.createXMLStreamReader(in);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    handleStart(reader, stack, seenThisFile);
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (!stack.isEmpty()) {
                        stack.peek().text.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    handleEnd(stack);
                }
            }
            reader.close();
        } catch (Exception e) {
            throw new IOException("Failed reading " + file + ": " + e.getMessage(), e);
        }
        for (ElementNode node : seenThisFile) {
            node.filesPresentIn++;
        }
    }

    private void handleStart(XMLStreamReader reader, Deque<Frame> stack, Set<ElementNode> seenThisFile) {
        QName qname = reader.getName();
        ElementNode node;
        if (stack.isEmpty()) {
            if (root == null) {
                root = new ElementNode(qname);
            }
            node = root;
        } else {
            Frame parentFrame = stack.peek();
            parentFrame.childCounts.merge(qname, 1, Integer::sum);
            parentFrame.childOrder.add(qname);
            node = parentFrame.node.children.computeIfAbsent(qname, ElementNode::new);
        }
        node.totalInstances++;
        seenThisFile.add(node);

        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String attrName = reader.getAttributeLocalName(i);
            ElementNode.AttrNode attr = node.attributes.computeIfAbsent(attrName, ElementNode.AttrNode::new);
            attr.presentCount++;
            attr.addSample(reader.getAttributeValue(i));
        }

        stack.push(new Frame(node));
    }

    private void handleEnd(Deque<Frame> stack) {
        Frame finished = stack.pop();
        ElementNode node = finished.node;
        if (!finished.childCounts.isEmpty()) {
            node.hasChildren = true;
            node.addOccurrenceChildSet(finished.childCounts.keySet());
            node.addOrderEdges(new ArrayList<>(finished.childOrder));
            for (Map.Entry<QName, Integer> entry : finished.childCounts.entrySet()) {
                ElementNode child = node.children.get(entry.getKey());
                child.presentInParentInstances++;
                child.maxRepeatPerInstance = Math.max(child.maxRepeatPerInstance, entry.getValue());
            }
        } else {
            String text = finished.text.toString().trim();
            if (!text.isEmpty()) {
                node.addTextSample(text);
            }
        }
    }

    private static final class Frame {
        final ElementNode node;
        final Map<QName, Integer> childCounts = new HashMap<>();
        final Set<QName> childOrder = new LinkedHashSet<>();
        final StringBuilder text = new StringBuilder();

        Frame(ElementNode node) {
            this.node = node;
        }
    }
}
