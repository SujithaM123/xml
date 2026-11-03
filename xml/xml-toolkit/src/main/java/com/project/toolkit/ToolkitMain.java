package com.project.toolkit;

import com.project.toolkit.generate.GenerateCommand;
import com.project.toolkit.generate.GenerateOptions;
import com.project.toolkit.generate.GenerateResult;
import com.project.toolkit.generate.ScanCommand;
import com.project.toolkit.generate.SchemaScanResult;
import com.project.toolkit.infer.InferCommand;
import com.project.toolkit.infer.InferOptions;
import java.nio.file.Path;

public final class ToolkitMain {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }
        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (command) {
                case "generate" -> runGenerate(rest);
                case "infer" -> runInfer(rest);
                case "scan" -> runScan(rest);
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runGenerate(String[] args) throws Exception {
        GenerateOptions opts = GenerateOptions.parse(args);
        // In "stream" mode, stdout IS the binary zip output -- human-readable status must not
        // share it, or it corrupts the stream for whatever is reading it downstream.
        java.io.PrintStream status = "stream".equals(opts.output) ? System.err : System.out;
        status.println("Generating " + opts.count + " XMLs from " + opts.xsd
                + (opts.out != null ? " into " + opts.out : "") + " ...");
        GenerateResult result = GenerateCommand.run(opts);
        status.println("Done.");
        result.print(status);
    }

    private static void runInfer(String[] args) throws Exception {
        InferOptions opts = InferOptions.parse(args);
        System.out.println("Inferring XSD from " + opts.xmlInput + " ...");
        InferCommand.run(opts);
        System.out.println("Done. Wrote " + opts.outXsd);
    }

    private static void runScan(String[] args) {
        if (args.length < 2 || !"--xsd".equals(args[0])) {
            throw new IllegalArgumentException("usage: scan --xsd <file>");
        }
        SchemaScanResult result = ScanCommand.run(Path.of(args[1]));
        result.print(System.out);
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java -jar xml-toolkit.jar generate --xsd <file> --out <folder> [--count N] [--size small|medium|large|mixed] [--seed N] [--level 1|2|3] [--profile <yaml>] [--output folder|zip|stream] [--threads N] [--error-percent N] [--error-types t1,t2] [--supplement <xsd>]
                  java -jar xml-toolkit.jar infer --xml <file or folder> --out <xsd file> [--baseline <xsd>]
                  java -jar xml-toolkit.jar scan --xsd <file>
                """);
    }
}
