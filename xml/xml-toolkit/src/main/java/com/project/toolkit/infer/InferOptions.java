package com.project.toolkit.infer;

import java.nio.file.Path;

public final class InferOptions {
    public Path xmlInput;
    public Path outXsd;
    public Path baselineXsd;

    public static InferOptions parse(String[] args) {
        InferOptions opts = new InferOptions();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--xml" -> opts.xmlInput = Path.of(requireValue(args, ++i, "--xml"));
                case "--out" -> opts.outXsd = Path.of(requireValue(args, ++i, "--out"));
                case "--baseline" -> opts.baselineXsd = Path.of(requireValue(args, ++i, "--baseline"));
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (opts.xmlInput == null) {
            throw new IllegalArgumentException("--xml is required");
        }
        if (opts.outXsd == null) {
            throw new IllegalArgumentException("--out is required");
        }
        if (!java.nio.file.Files.exists(opts.xmlInput)) {
            throw new IllegalArgumentException("--xml path not found: " + opts.xmlInput);
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
