package com.project.toolkit.infer;

import com.project.toolkit.validate.XsdValidatorCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class InferCommand {

    public static void run(InferOptions opts) throws IOException {
        List<Path> xmlFiles = collectXmlFiles(opts.xmlInput);
        if (xmlFiles.isEmpty()) {
            throw new IllegalArgumentException("No .xml files found at " + opts.xmlInput);
        }

        XmlStructureScanner scanner = new XmlStructureScanner();
        ElementNode root = scanner.scan(xmlFiles);

        if (opts.outXsd.getParent() != null) {
            Files.createDirectories(opts.outXsd.getParent());
        }
        XsdWriter xsdWriter = new XsdWriter();
        xsdWriter.write(root, opts.outXsd);
        System.out.println("  complexTypes: " + xsdWriter.typeCountAfterMerge()
                + " (before merging structurally identical types: " + xsdWriter.typeCountBeforeMerge() + ")");

        String reportName = stripExtension(opts.outXsd.getFileName().toString()) + "_review.md";
        Path reportPath = opts.outXsd.resolveSibling(reportName);
        if (opts.baselineXsd != null) {
            new ReviewReportWriter().writeWithBaseline(root, scanner.fileCount, reportPath, opts.baselineXsd);
        } else {
            new ReviewReportWriter().write(root, scanner.fileCount, reportPath);
        }

        System.out.println("Review report: " + reportPath);
        System.out.println("Validating " + xmlFiles.size() + " input XML(s) against the inferred XSD...");
        int valid = 0;
        for (Path xml : xmlFiles) {
            var result = XsdValidatorCache.validate(opts.outXsd, xml);
            if (result.valid()) {
                valid++;
            } else {
                System.out.println("  [FAIL] " + xml.getFileName() + ": " + result.reason());
            }
        }
        System.out.println("  " + valid + " / " + xmlFiles.size() + " valid against the inferred XSD");
    }

    private static List<Path> collectXmlFiles(Path input) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(input)) {
            try (Stream<Path> stream = Files.list(input)) {
                stream.filter(p -> p.toString().endsWith(".xml"))
                        .sorted(Comparator.naturalOrder())
                        .forEach(files::add);
            }
        } else {
            files.add(input);
        }
        return files;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
