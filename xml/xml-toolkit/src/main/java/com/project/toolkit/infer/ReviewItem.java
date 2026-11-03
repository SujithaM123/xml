package com.project.toolkit.infer;

/** One reviewable guess the inferrer made, with enough evidence for a human to judge it. */
record ReviewItem(String path, Kind kind, String guess, String evidence, Confidence confidence) {

    enum Kind { REQUIRED_OPTIONAL, REPEATS, ENUMERATION, CHOICE, DATATYPE, FORMAT }

    enum Confidence { HIGH, MEDIUM, LOW }

    String toMarkdownLine() {
        return "- " + path + ": " + evidence + " -> " + guess + ". Please confirm.";
    }

    String toJson() {
        return "{\"path\":" + jsonString(path)
                + ",\"kind\":" + jsonString(kind.name())
                + ",\"guess\":" + jsonString(guess)
                + ",\"evidence\":" + jsonString(evidence)
                + ",\"confidence\":" + jsonString(confidence.name())
                + "}";
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
