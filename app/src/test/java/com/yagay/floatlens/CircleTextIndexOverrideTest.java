package com.yagay.floatlens;

import android.graphics.Rect;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CircleTextIndexOverrideTest {
    @Test public void roiPatchOverridesViewLineInsideRequestedRegion() {
        OcrDocument.CharUnit viewChar = new OcrDocument.CharUnit(
                "A", new Rect(10, 10, 80, 50), 1f, 0, 0, 0);
        OcrDocument.Line viewLine = new OcrDocument.Line(
                "A", new Rect(10, 10, 80, 50), 1f, List.of(viewChar));
        OcrDocument view = OcrDocument.screenSpace(
                "A", List.of("A"), List.of(viewLine), "view", 1f, 1d, 200, 100);

        CircleTextIndex index = new CircleTextIndex(view, 200, 100);

        OcrDocument.CharUnit roiChar = new OcrDocument.CharUnit(
                "B", new Rect(12, 12, 75, 48), .9f, 0, 0, 0);
        OcrDocument.Line roiLine = new OcrDocument.Line(
                "B", new Rect(12, 12, 75, 48), .9f, List.of(roiChar));
        OcrDocument patch = OcrDocument.screenSpace(
                "B", List.of("B"), List.of(roiLine), "roi", .9f, 1d, 200, 100);

        index.replaceOcrRegion(patch, new Rect(0, 0, 100, 60));
        OcrDocument merged = index.current();

        assertTrue(merged.fullText().contains("B"));
        assertFalse(merged.fullText().contains("A"));
    }
}
