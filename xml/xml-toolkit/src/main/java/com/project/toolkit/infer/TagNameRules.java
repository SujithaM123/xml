package com.project.toolkit.infer;

/**
 * ISO 20022-style naming conventions used as a hint for the leaf datatype, applied before
 * falling back to pure value-sniffing in {@link DatatypeGuesser}. These are suffix/prefix
 * conventions used across the SWIFT/ISO 20022 message family (not specific to any one schema).
 */
final class TagNameRules {

    enum Hint {
        DATE, DATE_TIME, AMOUNT, BOOLEAN, TEXT_KEEP_LEADING_ZEROS, CODE, NONE
    }

    private TagNameRules() {
    }

    static Hint hintFor(String localName) {
        String n = localName;
        if (n.endsWith("DtTm")) {
            return Hint.DATE_TIME;
        }
        if (n.endsWith("Dt")) {
            return Hint.DATE;
        }
        if (n.endsWith("Ind") || n.endsWith("Flg")) {
            return Hint.BOOLEAN;
        }
        if (n.endsWith("Amt")) {
            return Hint.AMOUNT;
        }
        if (n.endsWith("Id") || n.endsWith("Ref") || n.endsWith("Nb")) {
            return Hint.TEXT_KEEP_LEADING_ZEROS;
        }
        if (n.endsWith("Cd") || n.endsWith("Tp")) {
            return Hint.CODE;
        }
        return Hint.NONE;
    }

    /** True for the Id/Ref/Nb family -- used to keep these out of enumeration suggestions too,
     * since a reference number repeating a handful of times across a small sample is coincidence,
     * not a closed value set. */
    static boolean isIdentifierLike(String localName) {
        return hintFor(localName) == Hint.TEXT_KEEP_LEADING_ZEROS;
    }

    static boolean isAmountField(String localName) {
        return hintFor(localName) == Hint.AMOUNT;
    }
}
