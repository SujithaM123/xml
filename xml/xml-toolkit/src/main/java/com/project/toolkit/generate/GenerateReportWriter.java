package com.project.toolkit.generate;

import java.io.IOException;

/** Writes the full per-run report (A11): settings, seed, counts, size stats, time and
 * files/second, in both JSON and CSV, through the same {@link OutputTarget} as the generated
 * files themselves. The per-file manifest (A6) is a separate pair of files written alongside it. */
final class GenerateReportWriter {

    private GenerateReportWriter() {
    }

    static void write(GenerateOptions opts, GenerateResult result, OutputTarget target) throws IOException {
        target.write("report.json", toJson(opts, result));
        target.write("report.csv", toCsv(opts, result));
    }

    private static String toJson(GenerateOptions opts, GenerateResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"settings\":{\n")
                .append("    \"xsd\":").append(q(String.valueOf(opts.xsd))).append(",\n")
                .append("    \"out\":").append(q(opts.out == null ? null : String.valueOf(opts.out))).append(",\n")
                .append("    \"output\":").append(q(opts.output)).append(",\n")
                .append("    \"count\":").append(opts.count).append(",\n")
                .append("    \"size\":").append(q(opts.size)).append(",\n")
                .append("    \"level\":").append(opts.level).append(",\n")
                .append("    \"threads\":").append(opts.threads).append(",\n")
                .append("    \"errorPercent\":").append(opts.errorPercent).append(",\n")
                .append("    \"errorTypes\":").append(opts.errorTypes == null ? "null" : listJson(opts.errorTypes)).append(",\n")
                .append("    \"supplement\":").append(q(opts.supplement == null ? null : String.valueOf(opts.supplement))).append(",\n")
                .append("    \"profile\":").append(q(opts.profile == null ? null : String.valueOf(opts.profile))).append('\n')
                .append("  },\n")
                .append("  \"seed\":").append(opts.seed).append(",\n")
                .append("  \"counts\":{\n")
                .append("    \"totalRequested\":").append(result.totalRequested).append(",\n")
                .append("    \"valid\":").append(result.validCount).append(",\n")
                .append("    \"invalid\":").append(result.invalidCount).append(",\n")
                .append("    \"errorByType\":").append(mapJson(result.errorCountByType)).append('\n')
                .append("  },\n")
                .append("  \"sizeBytes\":{\n")
                .append("    \"min\":").append(result.writtenCount() == 0 ? 0 : result.minBytes).append(",\n")
                .append("    \"avg\":").append(String.format("%.1f", result.avgBytes())).append(",\n")
                .append("    \"max\":").append(result.maxBytes).append('\n')
                .append("  },\n")
                .append("  \"timeSeconds\":").append(String.format("%.3f", result.elapsedMillis / 1000.0)).append(",\n")
                .append("  \"filesPerSecond\":").append(String.format("%.2f", result.xmlsPerSecond())).append('\n')
                .append("}\n");
        return sb.toString();
    }

    private static String toCsv(GenerateOptions opts, GenerateResult result) {
        StringBuilder sb = new StringBuilder("key,value\n");
        sb.append("xsd,").append(csv(String.valueOf(opts.xsd))).append('\n');
        sb.append("out,").append(csv(opts.out == null ? "" : String.valueOf(opts.out))).append('\n');
        sb.append("output,").append(csv(opts.output)).append('\n');
        sb.append("count,").append(opts.count).append('\n');
        sb.append("size,").append(csv(opts.size)).append('\n');
        sb.append("level,").append(opts.level).append('\n');
        sb.append("threads,").append(opts.threads).append('\n');
        sb.append("seed,").append(opts.seed).append('\n');
        sb.append("errorPercent,").append(opts.errorPercent).append('\n');
        sb.append("errorTypes,").append(csv(opts.errorTypes == null ? "" : String.join(";", opts.errorTypes))).append('\n');
        sb.append("supplement,").append(csv(opts.supplement == null ? "" : String.valueOf(opts.supplement))).append('\n');
        sb.append("totalRequested,").append(result.totalRequested).append('\n');
        sb.append("valid,").append(result.validCount).append('\n');
        sb.append("invalid,").append(result.invalidCount).append('\n');
        for (var e : result.errorCountByType.entrySet()) {
            sb.append("error.").append(e.getKey()).append(',').append(e.getValue()).append('\n');
        }
        sb.append("minSizeBytes,").append(result.writtenCount() == 0 ? 0 : result.minBytes).append('\n');
        sb.append("avgSizeBytes,").append(String.format("%.1f", result.avgBytes())).append('\n');
        sb.append("maxSizeBytes,").append(result.maxBytes).append('\n');
        sb.append("timeSeconds,").append(String.format("%.3f", result.elapsedMillis / 1000.0)).append('\n');
        sb.append("filesPerSecond,").append(String.format("%.2f", result.xmlsPerSecond())).append('\n');
        return sb.toString();
    }

    private static String listJson(java.util.List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String mapJson(java.util.Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var e : map.entrySet()) {
            sb.append(i++ == 0 ? "" : ",").append(q(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
