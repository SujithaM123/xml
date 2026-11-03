package com.project.toolkit.generate;

import javax.xml.namespace.QName;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Produces realistic leaf text values. Prefers XSD facets (enumeration/pattern) when present,
 * otherwise looks at the element's local name to pick a plausible value (amount, currency,
 * date, name, etc.), and falls back to a type-appropriate generic value.
 */
final class ValueGenerator {

    // A fixed anchor rather than LocalDate.now(): dates must be a pure function of the seed so
    // "--seed N" reproduces byte-identical output regardless of when the command actually runs.
    private static final LocalDate RECENT_ANCHOR = LocalDate.of(2026, 1, 1);

    /** RgxGen's default unbounded-quantifier repeat count is 100, which blows up badly on a
     * pattern with NESTED unbounded quantifiers (several real facets in these XSDs look like
     * {@code (X(X*(/X)?)*)}, where two nested {@code *} compound to roughly 100x100 characters)
     * -- confirmed empirically to produce multi-KB values against a 16-character maxLength.
     * A small bound here (matching the old hand-rolled sampler's 0-5/1-5 ranges) keeps generated
     * values close enough to typical facet lengths that {@link #sampleWithinLength} usually
     * finds one that fits on retry, without special-casing nesting depth. */
    private static final com.github.curiousoddman.rgxgen.config.RgxGenProperties PATTERN_PROPERTIES = boundedPatternProperties();

    private static com.github.curiousoddman.rgxgen.config.RgxGenProperties boundedPatternProperties() {
        var props = new com.github.curiousoddman.rgxgen.config.RgxGenProperties();
        com.github.curiousoddman.rgxgen.config.RgxGenOption.INFINITE_PATTERN_REPETITION.setInProperties(props, 3);
        return props;
    }

    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "JPY", "CHF", "INR", "AUD", "CAD", "SGD", "HKD"};
    private static final String[] COUNTRIES = {"US", "GB", "DE", "FR", "JP", "IN", "AU", "CA", "SG", "HK", "NL", "CH"};
    private static final String[] CITIES = {"New York", "London", "Frankfurt", "Paris", "Tokyo", "Mumbai", "Sydney", "Toronto", "Singapore", "Zurich"};
    private static final String[] GIVEN_NAMES = {"James", "Mary", "Robert", "Patricia", "John", "Linda", "Michael", "Elizabeth", "David", "Susan"};
    private static final String[] SURNAMES = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez"};

    private final Random rnd;
    private final Map<String, com.github.curiousoddman.rgxgen.RgxGen> patternCache = new java.util.HashMap<>();
    private final NameValueProfile profile;
    private final List<String> schemaElementNames;
    private final String rootElementName;

    ValueGenerator(Random rnd, NameValueProfile profile, List<String> schemaElementNames, String rootElementName) {
        this.rnd = rnd;
        this.profile = profile;
        this.schemaElementNames = schemaElementNames;
        this.rootElementName = rootElementName;
    }

    String generate(String elementLocalName, ResolvedSimpleType type) {
        if (!type.enumerationValues.isEmpty()) {
            return type.enumerationValues.get(rnd.nextInt(type.enumerationValues.size()));
        }
        if (profile.ruleFor(elementLocalName) == NameValueProfile.Rule.ELEMENT_PATH) {
            return clampToLength(elementPath(), type);
        }
        // Currency/country have small, well-known real code lists ("real ISO codes", not just
        // "looks plausible"), so try those before falling through to pattern sampling -- but
        // only accept one if it actually fits this type's own facets, so a schema that further
        // restricts the code list still gets a schema-valid value.
        String realCode = realCodeForWellKnownField(elementLocalName, type);
        if (realCode != null) {
            return realCode;
        }
        if (type.pattern != null) {
            String sampled = sampleWithinLength(type);
            if (sampled != null) {
                return sampled;
            }
        }
        String byName = byElementName(elementLocalName, type);
        if (byName != null) {
            return clampToLength(byName, type);
        }
        return byPrimitive(type);
    }

    /** A path to another element of this same message, e.g. "/Document/CorpActnGnlInf" --
     * for fields like PlcAndNm whose name looks like "place and name" but whose real purpose,
     * per the ISO 20022 usage this toolkit targets, is to point at a path within the message. */
    private String elementPath() {
        StringBuilder sb = new StringBuilder("/Document/").append(rootElementName);
        if (schemaElementNames.isEmpty()) {
            return sb.toString();
        }
        int segments = 1 + rnd.nextInt(3);
        for (int i = 0; i < segments; i++) {
            sb.append('/').append(schemaElementNames.get(rnd.nextInt(schemaElementNames.size())));
        }
        return sb.toString();
    }

    private String realCodeForWellKnownField(String name, ResolvedSimpleType type) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] candidates = null;
        if (n.contains("ccy") || n.equals("currency") || n.contains("curr")) {
            candidates = CURRENCIES;
        } else if (n.contains("ctry") || n.contains("country")) {
            candidates = COUNTRIES;
        }
        if (candidates == null) {
            return null;
        }
        List<String> fitting = new ArrayList<>();
        for (String c : candidates) {
            if (fitsConstraints(c, type)) {
                fitting.add(c);
            }
        }
        return fitting.isEmpty() ? null : fitting.get(rnd.nextInt(fitting.size()));
    }

    private boolean fitsConstraints(String candidate, ResolvedSimpleType type) {
        if (type.length != null && candidate.length() != type.length) {
            return false;
        }
        if (type.minLength != null && candidate.length() < type.minLength) {
            return false;
        }
        if (type.maxLength != null && candidate.length() > type.maxLength) {
            return false;
        }
        if (type.pattern != null) {
            try {
                if (!java.util.regex.Pattern.matches(type.pattern, candidate)) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false; // pattern syntax we can't verify in Java regex -- don't risk it
            }
        }
        return true;
    }

    private String sampleWithinLength(ResolvedSimpleType type) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = tryPattern(type.pattern);
            if (candidate == null) {
                return null;
            }
            if (type.length != null && candidate.length() != type.length) {
                continue;
            }
            if (type.minLength != null && candidate.length() < type.minLength) {
                continue;
            }
            if (type.maxLength != null && candidate.length() > type.maxLength) {
                continue;
            }
            return candidate;
        }
        return tryPattern(type.pattern);
    }

    /** RgxGen instances are cached per pattern string since the same facet pattern (e.g. a
     * currency or country code type) typically recurs across many leaves in one document. */
    private String tryPattern(String pattern) {
        try {
            com.github.curiousoddman.rgxgen.RgxGen generator = patternCache.computeIfAbsent(pattern, p -> {
                var g = new com.github.curiousoddman.rgxgen.RgxGen(p);
                g.setProperties(PATTERN_PROPERTIES);
                return g;
            });
            return generator.generate(rnd);
        } catch (RuntimeException e) {
            return null; // pattern RgxGen can't parse -- caller falls back to byElementName/byPrimitive
        }
    }

    private String byElementName(String name, ResolvedSimpleType type) {
        // The actual declared primitive always wins over a name guess: a field named e.g.
        // "SttlmRate" or "...Amt" could still legitimately be typed xs:date in some schema.
        String primitive = type.primitive.getLocalPart();
        if ("dateTime".equals(primitive)) {
            return realisticDateTime();
        }
        if ("date".equals(primitive)) {
            return realisticDate();
        }
        if ("boolean".equals(primitive)) {
            return String.valueOf(rnd.nextBoolean());
        }

        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("ccy") || n.equals("currency") || n.contains("curr")) {
            return CURRENCIES[rnd.nextInt(CURRENCIES.length)];
        }
        if (n.contains("ctry") || n.contains("country")) {
            return COUNTRIES[rnd.nextInt(COUNTRIES.length)];
        }
        if (n.contains("twnnm") || n.contains("city") || n.contains("town")) {
            return CITIES[rnd.nextInt(CITIES.length)];
        }
        if (n.contains("amt") || n.contains("amount") || n.contains("qty") || n.contains("rate") || n.contains("prc") || n.contains("price")) {
            return realisticDecimal(type);
        }
        if (n.contains("nm") || n.contains("name")) {
            return randomFullName();
        }
        if (n.contains("isin")) {
            return randomIsin();
        }
        if (n.contains("bic") || n.contains("anybic")) {
            return randomBic();
        }
        if (n.contains("iban") || n.contains("acct") || n.contains("account")) {
            return randomAccount();
        }
        if (n.contains("id") || n.contains("ref")) {
            return "REF" + String.format("%010d", Math.abs(rnd.nextLong() % 10_000_000_000L));
        }
        return null;
    }

    private String byPrimitive(ResolvedSimpleType type) {
        String local = type.primitive.getLocalPart();
        return switch (local) {
            case "boolean" -> String.valueOf(rnd.nextBoolean());
            case "integer", "int", "long", "short", "nonNegativeInteger", "positiveInteger" -> String.valueOf(positiveBounded(type, 1, 1_000_000));
            case "decimal" -> realisticDecimal(type);
            case "date" -> realisticDate();
            case "dateTime" -> realisticDateTime();
            case "gYear" -> String.valueOf(2015 + rnd.nextInt(10));
            case "gYearMonth" -> String.format("%04d-%02d", 2015 + rnd.nextInt(10), 1 + rnd.nextInt(12));
            default -> genericString(type);
        };
    }

    private long positiveBounded(ResolvedSimpleType type, long defaultMin, long defaultMax) {
        long min = type.minInclusive != null ? parseLongSafe(type.minInclusive, defaultMin) : defaultMin;
        long max = type.maxInclusive != null ? parseLongSafe(type.maxInclusive, defaultMax) : defaultMax;
        if (max <= min) {
            max = min + 1;
        }
        return min + (Math.abs(rnd.nextLong()) % (max - min));
    }

    private long parseLongSafe(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Builds the decimal as a scaled integer (value * 10^fraction) rather than rounding a
     * double: a double's full-precision fractional noise can carry more significant digits
     * than {@code totalDigits} allows once fraction is large, even though XSD's totalDigits
     * ignores insignificant trailing zeros (so a double-rounded, genuinely random fraction can
     * overflow it where an all-zero fraction wouldn't).
     */
    private String realisticDecimal(ResolvedSimpleType type) {
        int fraction = Math.max(0, type.fractionDigits != null ? type.fractionDigits : 2);
        double minD = type.minInclusive != null ? parseDoubleSafe(type.minInclusive, 1) : 1;
        double maxD = type.maxInclusive != null ? parseDoubleSafe(type.maxInclusive, 100_000) : 100_000;
        if (maxD <= minD) {
            maxD = minD + 1000;
        }

        BigInteger scale = BigInteger.TEN.pow(fraction);
        BigInteger minScaled = BigDecimal.valueOf(minD).multiply(new BigDecimal(scale)).toBigInteger();
        BigInteger maxScaled = BigDecimal.valueOf(maxD).multiply(new BigDecimal(scale)).toBigInteger();

        if (type.totalDigits != null) {
            BigInteger digitCap = BigInteger.TEN.pow(type.totalDigits).subtract(BigInteger.ONE);
            if (maxScaled.compareTo(digitCap) > 0) {
                maxScaled = digitCap;
            }
            if (minScaled.compareTo(maxScaled) > 0) {
                minScaled = BigInteger.ZERO;
            }
        }
        if (maxScaled.compareTo(minScaled) <= 0) {
            maxScaled = minScaled.add(BigInteger.ONE);
        }

        BigInteger range = maxScaled.subtract(minScaled);
        BigInteger offset = new BigInteger(range.bitLength() + 8, rnd).mod(range.add(BigInteger.ONE));
        BigInteger chosen = minScaled.add(offset);

        return new BigDecimal(chosen, fraction).toPlainString();
    }

    private double parseDoubleSafe(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String realisticDate() {
        LocalDate date = RECENT_ANCHOR.minusDays(rnd.nextInt(365 * 3));
        return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private String realisticDateTime() {
        LocalDateTime dt = RECENT_ANCHOR.atStartOfDay().minusMinutes(rnd.nextInt(60 * 24 * 365 * 3));
        return dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String randomFullName() {
        return GIVEN_NAMES[rnd.nextInt(GIVEN_NAMES.length)] + " " + SURNAMES[rnd.nextInt(SURNAMES.length)];
    }

    private String randomIsin() {
        String country = COUNTRIES[rnd.nextInt(COUNTRIES.length)];
        StringBuilder sb = new StringBuilder(country);
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('0' + rnd.nextInt(10)));
        }
        sb.append(rnd.nextInt(10));
        return sb.toString();
    }

    private String randomBic() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append((char) ('A' + rnd.nextInt(26)));
        sb.append(COUNTRIES[rnd.nextInt(COUNTRIES.length)]);
        for (int i = 0; i < 2; i++) sb.append((char) ('A' + rnd.nextInt(26)));
        return sb.toString();
    }

    private String randomAccount() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append((char) ('0' + rnd.nextInt(10)));
        }
        return sb.toString();
    }

    private String genericString(ResolvedSimpleType type) {
        int len = targetLength(type);
        List<String> words = List.of("corporate", "action", "movement", "settlement", "balance", "entitlement", "security", "custody", "notice", "advice");
        StringBuilder sb = new StringBuilder();
        while (sb.length() < len) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(words.get(rnd.nextInt(words.size())));
        }
        String s = sb.toString();
        return s.length() > len ? s.substring(0, len) : s;
    }

    private int targetLength(ResolvedSimpleType type) {
        if (type.length != null) return type.length;
        int min = type.minLength != null ? type.minLength : 3;
        int max = type.maxLength != null ? type.maxLength : Math.max(min, 20);
        if (max <= min) return min;
        return min + rnd.nextInt(max - min + 1);
    }

    private String clampToLength(String value, ResolvedSimpleType type) {
        if (type.maxLength != null && value.length() > type.maxLength) {
            return value.substring(0, type.maxLength);
        }
        if (type.length != null && value.length() != type.length) {
            if (value.length() > type.length) {
                return value.substring(0, type.length);
            }
            StringBuilder sb = new StringBuilder(value);
            while (sb.length() < type.length) sb.append('0');
            return sb.toString();
        }
        return value;
    }
}
