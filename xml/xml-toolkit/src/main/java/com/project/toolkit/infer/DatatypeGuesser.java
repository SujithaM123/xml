package com.project.toolkit.infer;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/** Guesses an XSD datatype from observed leaf text values; falls back to xs:string. */
final class DatatypeGuesser {

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+");

    private DatatypeGuesser() {
    }

    static String guess(List<String> samples) {
        return guess(samples, "");
    }

    /** {@code localName} applies the ISO 20022 tag-name conventions (see {@link TagNameRules})
     * as a hint before falling back to pure value-sniffing -- most importantly, an Id/Ref/Nb
     * field is kept as xs:string even when every sample happens to look numeric, since coercing
     * it to a numeric type would be lossy for values with leading zeros. */
    static String guess(List<String> samples, String localName) {
        TagNameRules.Hint hint = TagNameRules.hintFor(localName);
        if (hint == TagNameRules.Hint.TEXT_KEEP_LEADING_ZEROS) {
            return "string";
        }
        if (samples.isEmpty()) {
            return "string";
        }
        if (hint == TagNameRules.Hint.BOOLEAN && allMatch(samples, s -> s.equals("true") || s.equals("false"))) {
            return "boolean";
        }
        if (hint == TagNameRules.Hint.DATE && allMatch(samples, DatatypeGuesser::isDate)) {
            return "date";
        }
        if (hint == TagNameRules.Hint.DATE_TIME && allMatch(samples, DatatypeGuesser::isDateTime)) {
            return "dateTime";
        }
        if (allMatch(samples, s -> s.equals("true") || s.equals("false"))) {
            return "boolean";
        }
        if (allMatch(samples, s -> INTEGER.matcher(s).matches() && fitsInt(s))) {
            return "integer";
        }
        if (allMatch(samples, s -> INTEGER.matcher(s).matches())) {
            return "long";
        }
        if (allMatch(samples, s -> DECIMAL.matcher(s).matches())) {
            return "decimal";
        }
        if (allMatch(samples, DatatypeGuesser::isDate)) {
            return "date";
        }
        if (allMatch(samples, DatatypeGuesser::isDateTime)) {
            return "dateTime";
        }
        return "string";
    }

    private static boolean fitsInt(String s) {
        try {
            Long.parseLong(s);
            long v = Long.parseLong(s);
            return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDate(String s) {
        try {
            DateTimeFormatter.ISO_LOCAL_DATE.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isDateTime(String s) {
        try {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            try {
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s);
                return true;
            } catch (DateTimeParseException e2) {
                return false;
            }
        }
    }

    private static boolean allMatch(List<String> samples, java.util.function.Predicate<String> predicate) {
        for (String s : samples) {
            if (s.isEmpty() || !predicate.test(s)) {
                return false;
            }
        }
        return true;
    }
}
