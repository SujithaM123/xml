package com.project.toolkit.infer;

import java.math.BigInteger;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects a handful of well-known ISO/SWIFT value formats directly from sample values, with
 * their own checksum where one exists. These are reported at "high" confidence (auto-accepted,
 * not shown in the review list) since the checksum (or the fixed ISO 4217/3166 code list)
 * confirms the guess far more strongly than a bare regex match would.
 */
final class FormatDetector {

    enum Format { ISIN, BIC, LEI, CURRENCY, COUNTRY, ISO_DATE, NONE }

    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern BIC = Pattern.compile("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern LEI = Pattern.compile("[0-9A-Z]{18}[0-9]{2}");

    private static final Set<String> ISO_4217 = Set.of(
            "USD", "EUR", "GBP", "JPY", "CHF", "INR", "AUD", "CAD", "SGD", "HKD", "CNY", "NZD", "SEK", "NOK", "ZAR");
    private static final Set<String> ISO_3166 = Set.of(
            "US", "GB", "DE", "FR", "JP", "IN", "AU", "CA", "SG", "HK", "NL", "CH", "CN", "NZ", "SE", "NO", "ZA");

    private FormatDetector() {
    }

    /** Only returns a format when EVERY sample matches it -- a single counter-example falls
     * through to the ordinary datatype guess rather than a wrong high-confidence claim. */
    static Format detect(List<String> samples) {
        if (samples.isEmpty()) {
            return Format.NONE;
        }
        if (allMatch(samples, FormatDetector::isIsin)) {
            return Format.ISIN;
        }
        if (allMatch(samples, s -> BIC.matcher(s).matches())) {
            return Format.BIC;
        }
        if (allMatch(samples, FormatDetector::isLei)) {
            return Format.LEI;
        }
        if (allMatch(samples, s -> ISO_4217.contains(s))) {
            return Format.CURRENCY;
        }
        if (allMatch(samples, s -> ISO_3166.contains(s))) {
            return Format.COUNTRY;
        }
        if (allMatch(samples, FormatDetector::isIsoDate)) {
            return Format.ISO_DATE;
        }
        return Format.NONE;
    }

    private static boolean isIsin(String s) {
        if (!ISIN.matcher(s).matches()) {
            return false;
        }
        return isinCheckDigit(s) == (s.charAt(11) - '0');
    }

    /** ISO 6166 check digit: Luhn mod 10 over the string with letters expanded to two digits
     * (A=10 .. Z=35) and the trailing check digit dropped before computing. */
    private static int isinCheckDigit(String isin) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < isin.length() - 1; i++) {
            char c = isin.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                digits.append(c - 'A' + 10);
            }
        }
        int sum = 0;
        boolean doubleIt = digits.length() % 2 != 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** LEI check digits (ISO 17442) are a mod-97 check over the 18-character prefix, same
     * letter-to-number expansion as IBAN ("00" appended before taking mod 97). */
    private static boolean isLei(String s) {
        if (!LEI.matcher(s).matches()) {
            return false;
        }
        StringBuilder expanded = new StringBuilder();
        for (int i = 0; i < 18; i++) {
            char c = s.charAt(i);
            expanded.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        expanded.append("00");
        BigInteger remainder = new BigInteger(expanded.toString()).mod(BigInteger.valueOf(97));
        int checkDigits = Integer.parseInt(s.substring(18));
        return (98 - remainder.intValue()) == checkDigits;
    }

    private static boolean isIsoDate(String s) {
        try {
            DateTimeFormatter.ISO_LOCAL_DATE.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
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
