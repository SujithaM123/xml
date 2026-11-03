package com.project.toolkit.api.util;

import com.project.toolkit.api.entity.FileGroup;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Groups a folder of sample XMLs by what message type they look like, so a mixed upload can be
 * inferred group-by-group rather than as one (likely nonsensical) merge. Peeks only the root
 * element and, when it has no namespace, its direct children's names -- not a full parse -- since
 * this only needs to decide "do these belong together", not infer structure.
 */
public final class FileGrouper {

    public record Result(List<FileGroup> groups, List<String> brokenFiles, List<String> unknownFiles) {
    }

    private FileGrouper() {
    }

    public static Result group(Path dir) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        Map<String, String[]> keyInfo = new HashMap<>(); // key -> [namespace, rootName, fingerprint]
        List<String> broken = new ArrayList<>();

        try (var files = Files.list(dir)) {
            List<Path> xmlFiles = files.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
            for (Path file : xmlFiles) {
                try {
                    Peek peek = peek(file, factory);
                    String fingerprint = peek.namespace.isEmpty() ? fingerprintOf(peek.childNames) : null;
                    String key = peek.namespace.isEmpty()
                            ? peek.rootName + "#" + fingerprint
                            : peek.namespace + ":" + peek.rootName;
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(file.getFileName().toString());
                    keyInfo.putIfAbsent(key, new String[]{peek.namespace, peek.rootName, fingerprint});
                } catch (Exception e) {
                    broken.add(file.getFileName().toString());
                }
            }
        }

        List<FileGroup> groups = new ArrayList<>();
        for (var entry : byKey.entrySet()) {
            String[] info = keyInfo.get(entry.getKey());
            groups.add(new FileGroup(entry.getKey(), info[0], info[1], info[2], entry.getValue()));
        }
        return new Result(groups, broken, List.of());
    }

    private record Peek(String namespace, String rootName, List<String> childNames) {
    }

    private static Peek peek(Path file, XMLInputFactory factory) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            String namespace = null;
            String rootName = null;
            List<String> childNames = new ArrayList<>();
            int depth = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 1) {
                        namespace = reader.getNamespaceURI();
                        rootName = reader.getLocalName();
                    } else if (depth == 2) {
                        childNames.add(reader.getLocalName());
                    } else {
                        break; // only need the root and its direct children
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
            reader.close();
            if (rootName == null) {
                throw new IllegalArgumentException("No root element found");
            }
            return new Peek(namespace == null ? "" : namespace, rootName, childNames);
        }
    }

    /** Sorted, deduplicated child names joined -- stable regardless of how many times each
     * child repeats or what order a particular sample happened to write them in. */
    private static String fingerprintOf(List<String> childNames) {
        List<String> distinct = new ArrayList<>(new TreeSet<>(childNames));
        return String.join(",", distinct);
    }
}
