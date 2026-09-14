package com.yagay.floatlens;

/** Pure quality gates shared by OCR scheduling code and JVM regression tests. */
final class OcrQualityPolicy {
    private static final int MIN_STRONG_MEANINGFUL = 10;
    private static final double MIN_STRONG_READABLE_RATIO = 0.78d;
    private static final double MIN_STRONG_SCORE = 110d;

    /**
     * Conservative ML Kit early-stop gate. Short snippets deliberately continue through enhanced
     * passes because saving a few recognitions is less important than preserving difficult-text
     * accuracy. Long readable text with replacement/control/private-use garbage never qualifies.
     */
    static boolean strongMlKitResult(String text, double score) {
        if (text == null || text.isBlank() || score < MIN_STRONG_SCORE) return false;
        int meaningful = 0;
        int visible = 0;
        int garbage = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            visible++;
            if (Character.isLetterOrDigit(cp) || isCjk(cp)) meaningful++;
            if (cp == 0xFFFD || Character.isISOControl(cp) || (cp >= 0xE000 && cp <= 0xF8FF)) {
                garbage++;
            }
        }
        if (meaningful < MIN_STRONG_MEANINGFUL || visible <= 0 || garbage > 0) return false;
        return meaningful / (double) visible >= MIN_STRONG_READABLE_RATIO;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private OcrQualityPolicy() {}
}
