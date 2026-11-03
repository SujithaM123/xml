package com.project.toolkit.generate;

/** One row of the per-run error-injection manifest (A6/A11): what happened to one generated file. */
public record ErrorManifestEntry(String fileName, boolean isError, String errorType,
                                  String elementPath, String originalValue, String injectedValue) {

    static ErrorManifestEntry valid(String fileName) {
        return new ErrorManifestEntry(fileName, false, null, null, null, null);
    }

    public String toCsvRow() {
        return csv(fileName) + "," + (isError ? "error" : "valid") + "," + csv(errorType) + ","
                + csv(elementPath) + "," + csv(originalValue) + "," + csv(injectedValue);
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String escaped = s.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
