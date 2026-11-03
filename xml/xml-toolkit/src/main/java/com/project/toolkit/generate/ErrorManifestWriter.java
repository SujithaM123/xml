package com.project.toolkit.generate;

import java.io.IOException;
import java.util.List;

/** Writes the per-file error-injection manifest (A6/A11) in both CSV and JSON, through the same
 * {@link OutputTarget} every generated file went through (folder, zip, or stream -- A10). */
final class ErrorManifestWriter {

    private ErrorManifestWriter() {
    }

    static void write(List<ErrorManifestEntry> entries, OutputTarget target) throws IOException {
        StringBuilder csv = new StringBuilder("fileName,status,errorType,elementPath,originalValue,injectedValue\n");
        for (ErrorManifestEntry e : entries) {
            csv.append(e.toCsvRow()).append('\n');
        }
        target.write("manifest.csv", csv.toString());

        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < entries.size(); i++) {
            ErrorManifestEntry e = entries.get(i);
            json.append("  {\"fileName\":").append(q(e.fileName()))
                    .append(",\"status\":").append(q(e.isError() ? "error" : "valid"))
                    .append(",\"errorType\":").append(q(e.errorType()))
                    .append(",\"elementPath\":").append(q(e.elementPath()))
                    .append(",\"originalValue\":").append(q(e.originalValue()))
                    .append(",\"injectedValue\":").append(q(e.injectedValue()))
                    .append('}').append(i == entries.size() - 1 ? "\n" : ",\n");
        }
        json.append("]\n");
        target.write("manifest.json", json.toString());
    }

    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
