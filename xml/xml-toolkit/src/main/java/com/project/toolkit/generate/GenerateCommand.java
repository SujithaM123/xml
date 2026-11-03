package com.project.toolkit.generate;

import com.project.toolkit.validate.XsdValidatorCache;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates N sample XMLs for one XSD: picks a size tier per file, streams it out with StAX,
 * validates it against the XSD, and regenerates (with a different seed) on validation failure.
 */
public final class GenerateCommand {

    private static final int MAX_REGENERATE_ATTEMPTS = 5;
    private static final int MAX_CALIBRATION_ATTEMPTS = 6;
    private static final int CALIBRATION_TRIALS_PER_ATTEMPT = 3;
    private static final long CALIBRATION_TRIAL_HARD_CAP_BYTES = 30_000_000L;
    private static final int MAX_REPEAT_FACTOR = 500;

    public static GenerateResult run(GenerateOptions opts) throws IOException {
        SchemaIndex index = new SchemaIndex(opts.xsd);
        String rootName = index.rootElement.getName();
        ContentLevel level = ContentLevel.fromNumber(opts.level);
        // --size only matters at level 3 (it drives the repeat-factor calibration below); at
        // levels 1-2 every file is exactly "1 occurrence of everything allowed at that level",
        // so there's no tier to plan or calibrate -- one flat, uncalibrated pseudo-tier for
        // bookkeeping (file naming, GenerateResult's per-tier counts) is enough.
        List<SizeTier> plan = level == ContentLevel.FULL
                ? buildSizePlan(opts.size, opts.count)
                : java.util.Collections.nCopies(opts.count, SizeTier.SMALL);
        NameValueProfile profile = opts.profile != null ? NameValueProfile.load(opts.profile) : NameValueProfile.defaultProfile();

        List<String> errorTypes = List.of();
        if (opts.errorPercent > 0) {
            SchemaScanResult scan = SchemaScanner.scan(index);
            errorTypes = opts.errorTypes == null
                    ? List.copyOf(scan.possibleErrorTypes)
                    : opts.errorTypes.stream().filter(scan.possibleErrorTypes::contains).toList();
            if (errorTypes.isEmpty()) {
                throw new IllegalArgumentException("--error-types has none this XSD supports; it supports: " + scan.possibleErrorTypes);
            }
        }
        List<ErrorManifestEntry> manifest = new ArrayList<>();

        SchemaIndex supplementIndex = opts.supplement != null ? new SchemaIndex(opts.supplement) : null;

        // Namespace prefix/declaration is written explicitly by XmlInstanceGenerator (fixed
        // prefix, declared once on the root), so repairing namespaces is deliberately left off --
        // its auto-prefix counter isn't seeded and would break "same seed -> same output".
        XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();

        // Calibrate once per distinct size tier in this run: the repeat factor needed to hit a
        // tier's target byte size is driven by the XSD's nesting shape, not by file content, so
        // one calibration per (xsd, tier) is reused across every file of that tier.
        Map<SizeTier, Integer> repeatFactorByTier = new EnumMap<>(SizeTier.class);
        if (level == ContentLevel.FULL) {
            for (SizeTier tier : distinctTiers(plan)) {
                repeatFactorByTier.put(tier, calibrateRepeatFactor(index, tier, outputFactory, opts.seed, profile));
            }
        } else {
            repeatFactorByTier.put(SizeTier.SMALL, 1);
        }

        GenerateResult result = new GenerateResult();
        result.totalRequested = opts.count;
        long start = System.currentTimeMillis();

        try (OutputTarget target = openOutputTarget(opts)) {
            // Each file is fully independent (its own seed, own output path, own retry loop), so
            // the per-file work submits to a thread pool -- "each thread with its own writer" is
            // already true of every task below, since each one creates its own XMLStreamWriter
            // rather than sharing one; only the final GenerateResult/manifest merge (not the file
            // generation itself) needs to happen back on this thread, to avoid synchronizing shared
            // mutable state across workers. The OutputTarget itself (folder or zip) is shared, but
            // each implementation documents how it stays safe under concurrent writes.
            List<FileOutcome> outcomes;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(opts.threads);
            try {
                List<String> finalErrorTypes = errorTypes;
                java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
                List<java.util.concurrent.Future<FileOutcome>> futures = new ArrayList<>(plan.size());
                for (int i = 0; i < plan.size(); i++) {
                    if (opts.cancelled != null && opts.cancelled.getAsBoolean()) {
                        break;
                    }
                    final int fileIndex = i;
                    futures.add(pool.submit(() -> {
                        FileOutcome outcome = processOneFile(index, rootName, plan.get(fileIndex), repeatFactorByTier,
                                opts, profile, level, finalErrorTypes, supplementIndex, fileIndex, target);
                        // Reported from whichever worker finishes, as soon as it finishes -- real
                        // progress, not submission order -- so a caller tracking job % sees it
                        // move as work actually completes rather than only at the very end.
                        if (opts.onFileDone != null) {
                            opts.onFileDone.accept(completed.incrementAndGet());
                        }
                        return outcome;
                    }));
                }
                outcomes = new ArrayList<>(futures.size());
                for (var future : futures) {
                    outcomes.add(future.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Generation interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException("Failed generating a file: " + e.getCause().getMessage(), e.getCause());
            } finally {
                pool.shutdown();
            }

            for (FileOutcome outcome : outcomes) {
                manifest.add(outcome.manifestEntry());
                if (outcome.failedAfterRetries()) {
                    result.invalidCount++;
                    System.err.println("  [WARN] " + outcome.manifestEntry().fileName() + " failed validation after "
                            + MAX_REGENERATE_ATTEMPTS + " attempts: " + outcome.lastReason());
                    continue;
                }
                if (outcome.manifestEntry().isError()) {
                    result.errorCountByType.merge(outcome.manifestEntry().errorType(), 1, Integer::sum);
                } else {
                    result.validCount++;
                }
                result.countPerTier.merge(outcome.tier(), 1, Integer::sum);
                result.noteFileSize(outcome.bytes());
            }

            result.elapsedMillis = System.currentTimeMillis() - start;
            // Written for every run, not just error-injection ones: a plain valid run is still
            // "N files, M bytes, T seconds" worth reporting, and the per-file manifest is what
            // makes report.json/csv's aggregate counts independently checkable (A11). In zip mode
            // these go beside the zip as loose files rather than inside it, so the artifact a
            // caller downloads is XML documents only -- reporting still happens every run, just
            // not bundled into that particular file. Folder mode already writes them as loose
            // files in the output folder (nothing to change); stream mode has no secondary
            // location to put them in, so they stay bundled in the one stream that exists.
            OutputTarget reportsTarget = "zip".equals(opts.output)
                    ? new FolderOutputTarget(opts.out.getParent() != null ? opts.out.getParent() : Path.of("."))
                    : target;
            try {
                ErrorManifestWriter.write(manifest, reportsTarget);
                GenerateReportWriter.write(opts, result, reportsTarget);
            } finally {
                if (reportsTarget != target) {
                    reportsTarget.close();
                }
            }
        }

        return result;
    }

    private static OutputTarget openOutputTarget(GenerateOptions opts) throws IOException {
        return switch (opts.output) {
            case "folder" -> new FolderOutputTarget(opts.out);
            case "zip" -> {
                if (opts.out.getParent() != null) {
                    Files.createDirectories(opts.out.getParent());
                }
                yield new ZipOutputTarget(Files.newOutputStream(opts.out));
            }
            case "stream" -> new ZipOutputTarget(opts.streamTarget != null ? opts.streamTarget : System.out);
            default -> throw new IllegalArgumentException("Unknown --output: " + opts.output);
        };
    }

    private record FileOutcome(ErrorManifestEntry manifestEntry, SizeTier tier, long bytes,
                                boolean failedAfterRetries, String lastReason) {
    }

    private static FileOutcome processOneFile(SchemaIndex index, String rootName, SizeTier tier,
                                               Map<SizeTier, Integer> repeatFactorByTier, GenerateOptions opts,
                                               NameValueProfile profile, ContentLevel level, List<String> errorTypes,
                                               SchemaIndex supplementIndex, int fileIndex, OutputTarget target) throws IOException {
        // Each task gets its own factory: StAX writer instances must not be shared across
        // threads, and a factory is cheap enough that creating one per file removes any doubt.
        XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
        int repeatFactor = repeatFactorByTier.get(tier);
        String fileName = rootName + "_" + tier.label() + "_" + (fileIndex + 1) + ".xml";
        boolean embedSupplement = supplementIndex != null;

        long baseFileSeed = opts.seed + (long) fileIndex * 7919L;
        boolean wantsError = !errorTypes.isEmpty()
                && new Random(baseFileSeed ^ 0xE4404L).nextInt(100) < opts.errorPercent;

        if (wantsError) {
            String chosenType = errorTypes.get(new Random(baseFileSeed ^ 0xC0DE1L).nextInt(errorTypes.size()));
            InjectionOutcome injected = injectOne(index, repeatFactor, baseFileSeed, outputFactory, fileName, target,
                    profile, level, chosenType, opts.xsd, supplementIndex, opts.supplement);
            return new FileOutcome(injected.entry(), tier, injected.bytes(), false, null);
        }

        boolean valid = false;
        String lastReason = null;
        String xml = null;
        for (int attempt = 0; attempt < MAX_REGENERATE_ATTEMPTS && !valid; attempt++) {
            long fileSeed = baseFileSeed + attempt * 104_729L;
            xml = generateToString(index, repeatFactor, fileSeed, outputFactory, profile, level, null, embedSupplement);
            if (embedSupplement) {
                xml = embedSupplements(xml, supplementIndex, opts.supplement, fileSeed ^ 0xABCDEFL);
            }
            var check = XsdValidatorCache.validateString(opts.xsd, xml);
            valid = check.valid();
            lastReason = check.reason();
        }
        target.write(fileName, xml);

        if (valid) {
            return new FileOutcome(ErrorManifestEntry.valid(fileName), tier, utf8Length(xml), false, null);
        }
        return new FileOutcome(ErrorManifestEntry.valid(fileName), tier, 0, true, lastReason);
    }

    private static long utf8Length(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private record InjectionOutcome(ErrorManifestEntry entry, long bytes) {
    }

    /** Generates one file with exactly one deliberate error of {@code errorType} and confirms it
     * now fails validation for that reason, per A6. Falls back to "invalid-characters" (a plain
     * post-serialization text edit that always applies to any non-empty document) whenever the
     * requested type turns out to have no eligible occurrence in this specific random document,
     * or -- as a last-resort safety net -- whenever the injected document unexpectedly still
     * validates. */
    private static InjectionOutcome injectOne(SchemaIndex index, int repeatFactor, long fileSeed,
                                               XMLOutputFactory outputFactory, String fileName, OutputTarget target,
                                               NameValueProfile profile, ContentLevel level, String errorType, Path xsdPath,
                                               SchemaIndex supplementIndex, Path supplementXsd) throws IOException {
        boolean embedSupplement = supplementIndex != null;
        if ("malformed-xml".equals(errorType)) {
            String valid = generateToString(index, repeatFactor, fileSeed, outputFactory, profile, level, null, embedSupplement);
            if (embedSupplement) {
                valid = embedSupplements(valid, supplementIndex, supplementXsd, fileSeed ^ 0xABCDEFL);
            }
            String corrupted = removeLastClosingTag(valid);
            target.write(fileName, corrupted);
            boolean failedAsExpected = !XsdValidatorCache.validateString(xsdPath, corrupted).valid();
            return new InjectionOutcome(new ErrorManifestEntry(fileName, failedAsExpected, "malformed-xml",
                    "(end of document)", "(well-formed XML)", "(root closing tag removed)"), utf8Length(corrupted));
        }
        if ("invalid-characters".equals(errorType)) {
            return applyInvalidCharacters(index, repeatFactor, fileSeed, outputFactory, fileName, target, profile, level,
                    xsdPath, supplementIndex, supplementXsd);
        }

        ErrorInjectionContext counting = ErrorInjectionContext.countingPass(errorType);
        generateToString(index, repeatFactor, fileSeed, outputFactory, profile, level, counting, embedSupplement);
        if (counting.eligibleCount() == 0) {
            // This document's random structure doesn't happen to contain anything eligible for
            // errorType (e.g. no enum-valued field ended up present) even though the schema
            // supports it in general -- fall back to the always-applicable type.
            return applyInvalidCharacters(index, repeatFactor, fileSeed, outputFactory, fileName, target, profile, level,
                    xsdPath, supplementIndex, supplementXsd);
        }

        Random decisionRnd = new Random(fileSeed ^ 0xC0DE2L);
        ErrorInjectionContext inject = new ErrorInjectionContext(errorType, decisionRnd.nextInt(counting.eligibleCount()));
        String xml = generateToString(index, repeatFactor, fileSeed, outputFactory, profile, level, inject, embedSupplement);
        if (embedSupplement) {
            xml = embedSupplements(xml, supplementIndex, supplementXsd, fileSeed ^ 0xABCDEFL);
        }
        if (XsdValidatorCache.validateString(xsdPath, xml).valid()) {
            // Safety net: shouldn't happen given how each type is constructed, but confirm --
            // don't claim a file is a validation-failing error example when it isn't one.
            return applyInvalidCharacters(index, repeatFactor, fileSeed, outputFactory, fileName, target, profile, level,
                    xsdPath, supplementIndex, supplementXsd);
        }
        target.write(fileName, xml);
        return new InjectionOutcome(new ErrorManifestEntry(fileName, true, errorType,
                inject.elementPath, inject.originalValue, inject.injectedValue), utf8Length(xml));
    }

    private static InjectionOutcome applyInvalidCharacters(SchemaIndex index, int repeatFactor, long fileSeed,
                                                             XMLOutputFactory outputFactory, String fileName, OutputTarget target,
                                                             NameValueProfile profile, ContentLevel level,
                                                             Path xsdPath, SchemaIndex supplementIndex,
                                                             Path supplementXsd) throws IOException {
        boolean embedSupplement = supplementIndex != null;
        String valid = generateToString(index, repeatFactor, fileSeed, outputFactory, profile, level, null, embedSupplement);
        if (embedSupplement) {
            valid = embedSupplements(valid, supplementIndex, supplementXsd, fileSeed ^ 0xABCDEFL);
        }
        String[] result = insertIllegalCharacter(valid);
        target.write(fileName, result[0]);
        boolean failedAsExpected = !XsdValidatorCache.validateString(xsdPath, result[0]).valid();
        return new InjectionOutcome(new ErrorManifestEntry(fileName, failedAsExpected, "invalid-characters",
                result[1], result[2], result[3]), utf8Length(result[0]));
    }

    /** Replaces every {@code <SupplementPlaceholder idx="N">} marker (written by
     * {@link XmlInstanceGenerator} when supplement embedding is on) with a freshly generated,
     * independently-validated document from {@code supplementXsd} -- A7's "validate the supl
     * part against its own XSD too", done before it's ever spliced into the outer document. */
    private static String embedSupplements(String xml, SchemaIndex supplementIndex, Path supplementXsd, long seed) throws IOException {
        // Matches both the self-closing form a StAX writer may choose for an empty element
        // (Woodstox) and the explicit open/close form (the JDK's default writer) -- which one
        // appears is an implementation detail of whatever StAX provider is on the classpath.
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile(
                "<SupplementPlaceholder idx=\"(\\d+)\"/>|<SupplementPlaceholder idx=\"(\\d+)\"></SupplementPlaceholder>");
        java.util.regex.Matcher m = placeholder.matcher(xml);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int occurrence = 0;
        while (m.find()) {
            sb.append(xml, last, m.start());
            sb.append(generateValidSupplementFragment(supplementIndex, supplementXsd, seed + occurrence * 999_331L));
            last = m.end();
            occurrence++;
        }
        sb.append(xml.substring(last));
        return sb.toString();
    }

    private static String generateValidSupplementFragment(SchemaIndex supplementIndex, Path supplementXsd, long seed) throws IOException {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        for (int attempt = 0; attempt < MAX_REGENERATE_ATTEMPTS; attempt++) {
            long trySeed = seed + attempt * 104_729L;
            String full = generateToString(supplementIndex, 1, trySeed, factory, NameValueProfile.defaultProfile(), ContentLevel.FULL, null, false);
            if (XsdValidatorCache.validateString(supplementXsd, full).valid()) {
                return full.replaceFirst("^<\\?xml[^>]*\\?>", "");
            }
        }
        throw new IOException("Could not generate a valid supplement document against " + supplementXsd
                + " after " + MAX_REGENERATE_ATTEMPTS + " attempts");
    }

    /** Removes the document's final closing tag (always the root's, since it's written last),
     * leaving an unclosed element -- guaranteed not well-formed. */
    private static String removeLastClosingTag(String xml) {
        int closeStart = xml.lastIndexOf("</");
        if (closeStart < 0) {
            return xml + "<";
        }
        int closeEnd = xml.indexOf('>', closeStart);
        return closeEnd < 0 ? xml.substring(0, closeStart) : xml.substring(0, closeStart) + xml.substring(closeEnd + 1);
    }

    /** Splices a raw XML 1.0-disallowed control character (U+0001) into the middle of the first
     * non-blank text node found -- verified to survive serialization (unlike writing it through
     * the StAX API, which silently strips it) and to be rejected by the XML parser on reload. */
    private static String[] insertIllegalCharacter(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(">([^<]+)<").matcher(xml);
        while (m.find()) {
            String text = m.group(1);
            if (text.isBlank()) {
                continue;
            }
            int mid = text.length() / 2;
            int insertAt = m.start(1) + mid;
            String corrupted = xml.substring(0, insertAt) + '\u0001' + xml.substring(insertAt);
            String injectedText = text.substring(0, mid) + '\u0001' + text.substring(mid);
            return new String[]{corrupted, "(a text value)", text, injectedText};
        }
        int firstClose = xml.indexOf('>');
        return new String[]{xml.substring(0, firstClose + 1) + '\u0001' + xml.substring(firstClose + 1),
                "(document start)", "", "\u0001"};
    }

    private static String generateToString(SchemaIndex index, int repeatFactor, long seed, XMLOutputFactory outputFactory,
                                            NameValueProfile profile, ContentLevel level, ErrorInjectionContext injection,
                                            boolean embedSupplementPlaceholder) throws IOException {
        try {
            Random rnd = new Random(seed);
            XmlInstanceGenerator generator = new XmlInstanceGenerator(index, repeatFactor, rnd, profile, level, injection, embedSupplementPlaceholder);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            XMLStreamWriter writer = new IndentingXmlStreamWriter(outputFactory.createXMLStreamWriter(bos, "UTF-8"));
            generator.writeDocument(writer);
            writer.flush();
            writer.close();
            return bos.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Failed generating in-memory XML: " + e.getMessage(), e);
        }
    }

    private static List<SizeTier> distinctTiers(List<SizeTier> plan) {
        List<SizeTier> distinct = new ArrayList<>();
        for (SizeTier tier : plan) {
            if (!distinct.contains(tier)) {
                distinct.add(tier);
            }
        }
        return distinct;
    }

    /**
     * Finds a repeat factor that gets this tier's output within 2x of its target byte size,
     * by generating a few throwaway trials and correcting the factor with a damped power-law
     * step (sqrt of the size ratio) each time. Nested maxOccurs-unbounded lists compound
     * multiplicatively, so a couple of corrections is normally enough; a hard byte cap on each
     * trial keeps a bad guess from blowing up memory while calibrating "large".
     */
    private static int calibrateRepeatFactor(SchemaIndex index, SizeTier tier,
                                              XMLOutputFactory outputFactory, long seed, NameValueProfile profile) {
        if (tier == SizeTier.SMALL) {
            return tier.initialRepeatFactor;
        }
        int factor = tier.initialRepeatFactor;
        for (int attempt = 0; attempt < MAX_CALIBRATION_ATTEMPTS; attempt++) {
            long size = medianTrialSize(index, factor, seed, outputFactory, attempt, profile);
            double ratio = (double) tier.targetBytes / Math.max(1, size);
            if (ratio >= 0.5 && ratio <= 2.0) {
                break;
            }
            int next = (int) Math.round(factor * Math.sqrt(ratio));
            next = Math.max(1, Math.min(next, factor * 4));
            next = Math.min(next, MAX_REPEAT_FACTOR);
            if (next == factor) {
                break;
            }
            factor = next;
        }
        return factor;
    }

    /** Several schema-random trials at the same factor, since optional/choice content can swing
     * a single trial's size a lot; the median is a much more stable signal to calibrate against. */
    private static long medianTrialSize(SchemaIndex index, int factor, long seed,
                                         XMLOutputFactory outputFactory, int attempt, NameValueProfile profile) {
        long[] sizes = new long[CALIBRATION_TRIALS_PER_ATTEMPT];
        for (int t = 0; t < sizes.length; t++) {
            sizes[t] = trialSize(index, factor, seed + attempt * 97L + t * 31L, outputFactory, profile);
        }
        java.util.Arrays.sort(sizes);
        return sizes[sizes.length / 2];
    }

    private static long trialSize(SchemaIndex index, int factor, long seed, XMLOutputFactory outputFactory, NameValueProfile profile) {
        try {
            Random rnd = new Random(seed ^ 0x5EEDL);
            // Calibration only ever runs for the FULL level (see run()), so the trial itself
            // always generates at FULL.
            XmlInstanceGenerator generator = new XmlInstanceGenerator(index, factor, rnd, profile, ContentLevel.FULL, null, false);
            BoundedByteArrayOutputStream bos = new BoundedByteArrayOutputStream(CALIBRATION_TRIAL_HARD_CAP_BYTES);
            XMLStreamWriter writer = outputFactory.createXMLStreamWriter(bos, "UTF-8");
            generator.writeDocument(writer);
            writer.flush();
            writer.close();
            return bos.size();
        } catch (TooBigException e) {
            return CALIBRATION_TRIAL_HARD_CAP_BYTES * 10; // signal "way too big" to force a strong downward correction
        } catch (Exception e) {
            return 1; // unreadable trial: signal "way too small" so calibration grows and tries again
        }
    }

    private static final class TooBigException extends RuntimeException {
    }

    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final long cap;

        BoundedByteArrayOutputStream(long cap) {
            this.cap = cap;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (size() + len > cap) {
                throw new TooBigException();
            }
            super.write(b, off, len);
        }
    }

    /** Mixed mode cycles small/medium/large roughly one third each, in a fixed repeating order. */
    private static List<SizeTier> buildSizePlan(String size, int count) {
        List<SizeTier> plan = new ArrayList<>(count);
        if (!"mixed".equals(size)) {
            SizeTier tier = SizeTier.valueOf(size.toUpperCase());
            for (int i = 0; i < count; i++) {
                plan.add(tier);
            }
            return plan;
        }
        SizeTier[] cycle = {SizeTier.SMALL, SizeTier.MEDIUM, SizeTier.LARGE};
        for (int i = 0; i < count; i++) {
            plan.add(cycle[i % cycle.length]);
        }
        return plan;
    }
}
