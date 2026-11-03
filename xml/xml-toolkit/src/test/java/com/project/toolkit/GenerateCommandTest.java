package com.project.toolkit;

import com.project.toolkit.generate.GenerateCommand;
import com.project.toolkit.generate.GenerateOptions;
import com.project.toolkit.generate.GenerateResult;
import com.project.toolkit.validate.XsdValidatorCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GenerateCommandTest {

    private static final Path SAMPLE_XSD = Path.of("..", "xsd", "SR_2025_Corporate_Action_CAPA_Extension_supl_009_001_11.xsd");
    private static final Path RESOURCES = Path.of("src", "test", "resources");

    @Test
    void defaultCountIsOneThousand() {
        GenerateOptions opts = GenerateOptions.parse(new String[]{"--xsd", SAMPLE_XSD.toString(), "--out", "x"});
        assertEquals(1000, opts.count);
    }

    @Test
    void generatedXmlsAreValid(@TempDir Path tempDir) throws IOException {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = SAMPLE_XSD;
        opts.out = tempDir;
        opts.count = 6;
        opts.size = "mixed";
        opts.seed = 42;

        GenerateResult result = GenerateCommand.run(opts);

        assertEquals(6, result.validCount + result.invalidCount);
        assertEquals(0, result.invalidCount, "every generated file should validate against its XSD");

        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> xmlFiles = files.filter(p -> p.toString().endsWith(".xml")).toList();
            assertEquals(6, xmlFiles.size());
            for (Path xml : xmlFiles) {
                assertTrue(XsdValidatorCache.validate(opts.xsd, xml).valid(), "expected " + xml + " to validate");
            }
        }
    }

    @Test
    void sameSeedGivesSameOutput(@TempDir Path tempDir) throws IOException {
        Path outA = tempDir.resolve("a");
        Path outB = tempDir.resolve("b");

        GenerateOptions optsA = new GenerateOptions();
        optsA.xsd = SAMPLE_XSD;
        optsA.out = outA;
        optsA.count = 3;
        optsA.seed = 123;

        GenerateOptions optsB = new GenerateOptions();
        optsB.xsd = SAMPLE_XSD;
        optsB.out = outB;
        optsB.count = 3;
        optsB.seed = 123;

        GenerateCommand.run(optsA);
        GenerateCommand.run(optsB);

        List<Path> filesA = sortedXmlFiles(outA);
        List<Path> filesB = sortedXmlFiles(outB);
        assertEquals(filesA.size(), filesB.size());
        for (int i = 0; i < filesA.size(); i++) {
            assertEquals(Files.readString(filesA.get(i)), Files.readString(filesB.get(i)),
                    "same seed should produce byte-identical XML");
        }
    }

    @Test
    void plcAndNmIsAnElementPathNotAPersonName(@TempDir Path tempDir) throws IOException {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = SAMPLE_XSD;
        opts.out = tempDir;
        opts.count = 30;
        opts.size = "medium";
        opts.seed = 7;

        GenerateCommand.run(opts);

        java.util.regex.Pattern tag = java.util.regex.Pattern.compile("PlcAndNm>([^<]*)<");
        boolean foundAny = false;
        try (Stream<Path> files = Files.list(tempDir)) {
            for (Path xml : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                var matcher = tag.matcher(Files.readString(xml));
                while (matcher.find()) {
                    String value = matcher.group(1);
                    if (value.isBlank()) {
                        continue;
                    }
                    foundAny = true;
                    assertTrue(value.startsWith("/Document/"), "PlcAndNm should be an element path, got: " + value);
                    for (String name : new String[]{"James", "Mary", "Robert", "Smith", "Johnson", "Williams"}) {
                        assertFalse(value.contains(name), "PlcAndNm should not contain a person's name, got: " + value);
                    }
                }
            }
        }
        assertTrue(foundAny, "expected at least one non-empty PlcAndNm across 30 generated files");
    }

    @Test
    void contentLevelsControlOptionalAndRepeatInclusion(@TempDir Path tempDir) throws IOException {
        Path outL1 = tempDir.resolve("l1");
        Path outL2 = tempDir.resolve("l2");

        GenerateOptions optsL1 = new GenerateOptions();
        optsL1.xsd = SAMPLE_XSD;
        optsL1.out = outL1;
        optsL1.count = 1;
        optsL1.level = 1;
        optsL1.seed = 5;
        GenerateCommand.run(optsL1);

        GenerateOptions optsL2 = new GenerateOptions();
        optsL2.xsd = SAMPLE_XSD;
        optsL2.out = outL2;
        optsL2.count = 1;
        optsL2.level = 2;
        optsL2.seed = 5;
        GenerateCommand.run(optsL2);

        long sizeL1 = Files.size(onlyXmlFile(outL1));
        long sizeL2 = Files.size(onlyXmlFile(outL2));
        assertTrue(sizeL1 < sizeL2, "level 1 (required only) should be smaller than level 2 (required + optional): "
                + sizeL1 + " vs " + sizeL2);
        assertTrue(XsdValidatorCache.validate(SAMPLE_XSD, onlyXmlFile(outL1)).valid(), "level 1 output should still validate");
        assertTrue(XsdValidatorCache.validate(SAMPLE_XSD, onlyXmlFile(outL2)).valid(), "level 2 output should still validate");
    }

    @Test
    void everyErrorTypeThisXsdSupportsReliablyFailsValidationForItsRecordedReason(@TempDir Path tempDir) throws IOException {
        for (String errorType : List.of("missing-required", "invalid-code", "pattern-break",
                "too-long", "wrong-datatype", "invalid-characters", "malformed-xml",
                "invalid-date", "missing-attribute", "invalid-attribute-value", "invalid-namespace", "unexpected-element")) {
            Path out = tempDir.resolve(errorType);
            GenerateOptions opts = new GenerateOptions();
            opts.xsd = SAMPLE_XSD;
            opts.out = out;
            opts.count = 3;
            opts.level = 2;
            opts.seed = 99;
            opts.errorPercent = 100;
            opts.errorTypes = List.of(errorType);

            GenerateCommand.run(opts);

            try (Stream<Path> files = Files.list(out)) {
                for (Path xml : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    assertFalse(XsdValidatorCache.validate(SAMPLE_XSD, xml).valid(),
                            errorType + ": " + xml.getFileName() + " should fail validation but didn't");
                }
            }

            String manifest = Files.readString(out.resolve("manifest.csv"));
            assertTrue(manifest.lines().skip(1).allMatch(l -> l.contains(",error,")),
                    errorType + ": every row should be marked as an error at --error-percent 100:\n" + manifest);
        }
    }

    @Test
    void embedsAValidSupplementDocumentInsideXsAnySlots(@TempDir Path tempDir) throws IOException {
        Path seev035 = Path.of("..", "xsd", "SR_2025_Corporate_Action_CAPA_seev_035_001_16.xsd");
        Path supl009 = Path.of("..", "xsd", "SR_2025_Corporate_Action_CAPA_Extension_supl_009_001_11.xsd");

        GenerateOptions opts = new GenerateOptions();
        opts.xsd = seev035;
        opts.out = tempDir;
        opts.count = 5;
        opts.level = 2;
        opts.seed = 3;
        opts.supplement = supl009;

        GenerateResult result = GenerateCommand.run(opts);
        assertEquals(0, result.invalidCount, "the outer document should still validate with a supplement embedded");

        boolean foundSupplement = false;
        try (Stream<Path> files = Files.list(tempDir)) {
            for (Path xml : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                String text = Files.readString(xml);
                int envlpStart = text.indexOf(":Envlp>");
                if (envlpStart < 0) {
                    continue;
                }
                assertTrue(text.contains("urn:iso:std:iso:20022:tech:xsd:supl.009.001.11"),
                        "Envlp should contain a document in the supl.009 namespace: " + xml);
                foundSupplement = true;

                // Extract just the embedded supl.009 Document and validate it independently
                // against its own XSD, per A7.
                int docStart = text.indexOf("<ns:Document xmlns:ns=\"urn:iso:std:iso:20022:tech:xsd:supl.009.001.11\">", envlpStart);
                int docEnd = text.indexOf("</ns:Document>", docStart) + "</ns:Document>".length();
                String fragment = text.substring(docStart, docEnd);
                Path fragFile = tempDir.resolve(xml.getFileName() + ".supplement.xml");
                Files.writeString(fragFile, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + fragment);
                assertTrue(XsdValidatorCache.validate(supl009, fragFile).valid(),
                        "embedded supplement fragment should independently validate against supl.009: " + xml);
            }
        }
        assertTrue(foundSupplement, "expected at least one SplmtryData/Envlp with an embedded supplement across 5 files");
    }

    @Test
    void sameSeedGivesSameOutputRegardlessOfThreadCount(@TempDir Path tempDir) throws IOException {
        Path outSingle = tempDir.resolve("single");
        Path outMulti = tempDir.resolve("multi");

        GenerateOptions optsSingle = new GenerateOptions();
        optsSingle.xsd = SAMPLE_XSD;
        optsSingle.out = outSingle;
        optsSingle.count = 9;
        optsSingle.seed = 55;
        optsSingle.threads = 1;

        GenerateOptions optsMulti = new GenerateOptions();
        optsMulti.xsd = SAMPLE_XSD;
        optsMulti.out = outMulti;
        optsMulti.count = 9;
        optsMulti.seed = 55;
        optsMulti.threads = 4;

        GenerateCommand.run(optsSingle);
        GenerateCommand.run(optsMulti);

        List<Path> filesSingle = sortedXmlFiles(outSingle);
        List<Path> filesMulti = sortedXmlFiles(outMulti);
        assertEquals(filesSingle.size(), filesMulti.size());
        for (int i = 0; i < filesSingle.size(); i++) {
            assertEquals(Files.readString(filesSingle.get(i)), Files.readString(filesMulti.get(i)),
                    "thread count should not affect output (file ordering/content must stay deterministic)");
        }
    }

    @Test
    void zipOutputContainsTheSameValidFilesAsFolderOutput(@TempDir Path tempDir) throws IOException {
        Path folderOut = tempDir.resolve("folder");
        Path zipOut = tempDir.resolve("out.zip");

        GenerateOptions folderOpts = new GenerateOptions();
        folderOpts.xsd = SAMPLE_XSD;
        folderOpts.out = folderOut;
        folderOpts.count = 4;
        folderOpts.level = 2;
        folderOpts.seed = 17;
        GenerateCommand.run(folderOpts);

        GenerateOptions zipOpts = new GenerateOptions();
        zipOpts.xsd = SAMPLE_XSD;
        zipOpts.out = zipOut;
        zipOpts.count = 4;
        zipOpts.level = 2;
        zipOpts.seed = 17;
        zipOpts.output = "zip";
        GenerateResult zipResult = GenerateCommand.run(zipOpts);
        assertEquals(0, zipResult.invalidCount);

        List<Path> folderFiles = sortedXmlFiles(folderOut);
        assertEquals(4, folderFiles.size());

        try (var zipFile = new java.util.zip.ZipFile(zipOut.toFile())) {
            assertEquals(4, zipFile.stream().filter(e -> e.getName().endsWith(".xml")).count());
            for (Path f : folderFiles) {
                var entry = zipFile.getEntry(f.getFileName().toString());
                assertNotNull(entry, "zip should contain " + f.getFileName());
                String zippedContent = new String(zipFile.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(Files.readString(f), zippedContent, "zip entry content should match the folder-mode file byte for byte");
            }
        }
    }

    @Test
    void zipOutputContainsOnlyXmlFilesWithReportsWrittenBesideIt(@TempDir Path tempDir) throws IOException {
        Path zipOut = tempDir.resolve("out.zip");

        GenerateOptions zipOpts = new GenerateOptions();
        zipOpts.xsd = SAMPLE_XSD;
        zipOpts.out = zipOut;
        zipOpts.count = 4;
        zipOpts.level = 2;
        zipOpts.seed = 23;
        zipOpts.output = "zip";
        GenerateCommand.run(zipOpts);

        try (var zipFile = new java.util.zip.ZipFile(zipOut.toFile())) {
            List<String> entryNames = zipFile.stream().map(java.util.zip.ZipEntry::getName).toList();
            assertEquals(4, entryNames.size(), "zip should contain only the 4 generated XML files: " + entryNames);
            assertTrue(entryNames.stream().allMatch(n -> n.endsWith(".xml")), "zip should contain only .xml entries: " + entryNames);
            assertTrue(entryNames.stream().noneMatch(n -> n.startsWith("report.") || n.startsWith("manifest.")),
                    "zip should not contain report/manifest files: " + entryNames);
        }

        // The reports/manifests still get generated every run -- just beside the zip, not inside it.
        Path jobDir = zipOut.getParent();
        for (String name : List.of("report.json", "report.csv", "manifest.json", "manifest.csv")) {
            assertTrue(Files.exists(jobDir.resolve(name)), name + " should be written beside the zip");
        }
    }

    @Test
    void everyRunWritesAReportAndManifestInJsonAndCsv(@TempDir Path tempDir) throws IOException {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = SAMPLE_XSD;
        opts.out = tempDir;
        opts.count = 6;
        opts.level = 2;
        opts.seed = 21;

        GenerateResult result = GenerateCommand.run(opts);

        for (String name : List.of("report.json", "report.csv", "manifest.json", "manifest.csv")) {
            assertTrue(Files.exists(tempDir.resolve(name)), name + " should always be written, not just for error-injection runs");
        }
        String reportJson = Files.readString(tempDir.resolve("report.json"));
        assertTrue(reportJson.contains("\"seed\":21"), "report.json should echo the seed used:\n" + reportJson);
        assertTrue(reportJson.contains("\"valid\":" + result.validCount), "report.json's valid count should match the real result:\n" + reportJson);

        long manifestRows = Files.readString(tempDir.resolve("manifest.csv")).lines().skip(1).count();
        assertEquals(6, manifestRows, "manifest should have one row per generated file");
    }

    @Test
    void complexContentExtensionIsFullyPopulatedNotEmpty(@TempDir Path tempDir) throws IOException {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = RESOURCES.resolve("complex-content-extension.xsd");
        opts.out = tempDir;
        opts.count = 5;
        opts.level = 2;
        opts.seed = 11;

        GenerateResult result = GenerateCommand.run(opts);
        assertEquals(0, result.invalidCount, "every field inherited through complexContent extension should be populated and valid");

        for (Path xml : xmlFilesIn(tempDir)) {
            String text = Files.readString(xml);
            // Each level of the two-deep extension chain (BaseType -> MidType -> ExtendedType)
            // contributes one field; none should be missing, and the element must not have
            // collapsed to an empty/self-closed tag.
            assertTrue(text.contains("<ns:BaseField>"), "base type's field missing:\n" + text);
            assertTrue(text.contains("<ns:MidField>"), "mid type's field missing:\n" + text);
            assertTrue(text.contains("<ns:ExtraField>"), "extending type's own field missing:\n" + text);
            assertTrue(text.contains("<ns:Count>"), "extending type's own field missing:\n" + text);
            assertFalse(text.contains("<ns:Body/>"), "Body should not have collapsed to an empty element:\n" + text);
        }
    }

    @Test
    void elementRefResolvesToItsTargetsNameAndType(@TempDir Path tempDir) throws IOException {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = RESOURCES.resolve("element-ref.xsd");
        opts.out = tempDir;
        opts.count = 3;
        opts.level = 2;
        opts.seed = 12;

        GenerateResult result = GenerateCommand.run(opts);
        assertEquals(0, result.invalidCount, "a <xs:element ref=\"...\"/> particle should resolve to its target's name and type");

        for (Path xml : xmlFilesIn(tempDir)) {
            String text = Files.readString(xml);
            assertTrue(text.matches("(?s).*<ns:PersonName>[^<]+</ns:PersonName>.*"), "ref-resolved element missing or empty:\n" + text);
        }
    }

    @Test
    void includedSchemaFileIsResolvedRelativeToTheMainXsd(@TempDir Path tempDir) throws IOException {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = RESOURCES.resolve("import-main.xsd");
        opts.out = tempDir;
        opts.count = 3;
        opts.level = 2;
        opts.seed = 13;

        GenerateResult result = GenerateCommand.run(opts);
        assertEquals(0, result.invalidCount, "a type from an xs:include'd file should resolve and populate normally");

        for (Path xml : xmlFilesIn(tempDir)) {
            String text = Files.readString(xml);
            assertTrue(text.contains("<ns:Street>") && text.contains("<ns:City>"), "included type's fields missing:\n" + text);
        }
    }

    @Test
    void importedSchemaAcrossNamespacesResolvesAndKeepsItsOwnNamespace(@TempDir Path tempDir) throws IOException {
        GenerateOptions opts = new GenerateOptions();
        opts.xsd = RESOURCES.resolve("import-ref-main.xsd");
        opts.out = tempDir;
        opts.count = 3;
        opts.level = 2;
        opts.seed = 14;

        GenerateResult result = GenerateCommand.run(opts);
        assertEquals(0, result.invalidCount, "a ref into an xs:import'd, differently-namespaced schema should resolve and validate");

        for (Path xml : xmlFilesIn(tempDir)) {
            String text = Files.readString(xml);
            assertTrue(text.contains("xmlns:ext=\"urn:test:import-ref-common\""), "cross-namespace element should declare its own namespace:\n" + text);
            assertTrue(text.matches("(?s).*<ext:PersonName[^>]*>[^<]+</ext:PersonName>.*"), "cross-namespace ref element missing or empty:\n" + text);
        }
    }

    private static List<Path> xmlFilesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".xml")).toList();
        }
    }

    private static Path onlyXmlFile(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".xml")).findFirst().orElseThrow();
        }
    }

    private static List<Path> sortedXmlFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
    }
}
