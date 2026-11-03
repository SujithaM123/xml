package com.project.toolkit.generate;

import java.nio.file.Path;

public final class GenerateOptions {
    private static final java.util.List<String> ALLOWED_SIZES = java.util.List.of("small", "medium", "large", "mixed");
    private static final java.util.List<String> ALLOWED_OUTPUTS = java.util.List.of("folder", "zip", "stream");

    public Path xsd;
    public Path out;
    public int count = 1000;
    public String size = "mixed"; // small | medium | large | mixed
    public long seed = System.nanoTime();
    public boolean seedExplicit = false;
    public Path profile;
    public int level = 3;
    public int errorPercent = 0;
    public java.util.List<String> errorTypes; // null = every type the XSD supports
    public Path supplement; // optional: another XSD to generate a valid document for and embed in each xs:any slot
    public int threads = Runtime.getRuntime().availableProcessors();
    /** folder (default, --out is a directory) | zip (--out is the archive file to create) |
     * stream (writes a ZIP byte stream to {@link #streamTarget}, or stdout if that's null --
     * for a programmatic caller, e.g. a future API response body; A10). */
    public String output = "folder";
    public java.io.OutputStream streamTarget;
    /** Optional: called with the number of files completed so far, each time one finishes --
     * for a caller (the API module's job tracker) that needs incremental progress rather than
     * just the final GenerateResult. Never called concurrently; invoked from the merge loop
     * after all per-file work has already happened in parallel. */
    public java.util.function.IntConsumer onFileDone;
    /** Optional: polled before queuing each new file's work. Once it returns true, no further
     * files are submitted -- already-running ones still finish (best-effort cancellation, the
     * same semantics most job systems give a "cancel" button). Null means never cancelled. */
    public java.util.function.BooleanSupplier cancelled;

    public static GenerateOptions parse(String[] args) {
        GenerateOptions opts = new GenerateOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--xsd" -> opts.xsd = Path.of(requireValue(args, ++i, "--xsd"));
                case "--out" -> opts.out = Path.of(requireValue(args, ++i, "--out"));
                case "--count" -> opts.count = Integer.parseInt(requireValue(args, ++i, "--count"));
                case "--size" -> opts.size = requireValue(args, ++i, "--size");
                case "--seed" -> {
                    opts.seed = Long.parseLong(requireValue(args, ++i, "--seed"));
                    opts.seedExplicit = true;
                }
                case "--profile" -> opts.profile = Path.of(requireValue(args, ++i, "--profile"));
                case "--level" -> opts.level = Integer.parseInt(requireValue(args, ++i, "--level"));
                case "--error-percent" -> opts.errorPercent = Integer.parseInt(requireValue(args, ++i, "--error-percent"));
                case "--error-types" -> opts.errorTypes = java.util.List.of(requireValue(args, ++i, "--error-types").split(","));
                case "--supplement" -> opts.supplement = Path.of(requireValue(args, ++i, "--supplement"));
                case "--threads" -> opts.threads = Integer.parseInt(requireValue(args, ++i, "--threads"));
                case "--output" -> opts.output = requireValue(args, ++i, "--output");
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        ContentLevel.fromNumber(opts.level); // validates range eagerly
        if (opts.errorPercent < 0 || opts.errorPercent > 100) {
            throw new IllegalArgumentException("--error-percent must be between 0 and 100, got: " + opts.errorPercent);
        }
        if (opts.threads < 1) {
            throw new IllegalArgumentException("--threads must be at least 1, got: " + opts.threads);
        }
        if (opts.xsd == null) {
            throw new IllegalArgumentException("--xsd is required");
        }
        if (!ALLOWED_OUTPUTS.contains(opts.output)) {
            throw new IllegalArgumentException("--output must be one of folder|zip|stream, got: " + opts.output);
        }
        // "stream" writes to an OutputStream (stdout by default), not a path on disk, so --out
        // names nothing in that mode.
        if (opts.out == null && !"stream".equals(opts.output)) {
            throw new IllegalArgumentException("--out is required");
        }
        if (!java.nio.file.Files.isRegularFile(opts.xsd)) {
            throw new IllegalArgumentException("XSD file not found: " + opts.xsd);
        }
        if (!ALLOWED_SIZES.contains(opts.size)) {
            throw new IllegalArgumentException("--size must be one of small|medium|large|mixed, got: " + opts.size);
        }
        return opts;
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }
}
