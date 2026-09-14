package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OcrQualityPolicyTest {
    @Test
    public void strongReadableChineseCanStopEarly() {
        assertTrue(OcrQualityPolicy.strongMlKitResult(
                "这是一个用于验证识别质量的中文句子", 180d));
    }

    @Test
    public void strongReadableEnglishCanStopEarly() {
        assertTrue(OcrQualityPolicy.strongMlKitResult(
                "FloatLens recognizes readable screen text", 220d));
    }

    @Test
    public void shortTextKeepsSearchingForBetterPass() {
        assertFalse(OcrQualityPolicy.strongMlKitResult("Settings", 300d));
    }

    @Test
    public void punctuationHeavyOrLowScoreKeepsSearching() {
        assertFalse(OcrQualityPolicy.strongMlKitResult("....////----Hello", 200d));
        assertFalse(OcrQualityPolicy.strongMlKitResult(
                "This text is readable but its score is weak", 90d));
    }

    @Test
    public void replacementGarbageNeverStopsEarly() {
        assertFalse(OcrQualityPolicy.strongMlKitResult(
                "Readable text with bad replacement \uFFFD data", 250d));
    }
}
