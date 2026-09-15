package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GestureClassifierTest {
    private static GestureSession session(float endX, float endY) {
        GestureSession s = new GestureSession();
        s.begin(0f, 0f, 0L);
        s.add(endX, endY, 10L);
        return s;
    }

    @Test
    public void movementBelowStartThresholdIsNone() {
        GestureDecision d = GestureClassifier.classify(
                session(8f, 6f), 12f, 1.2f, 80f, 100f);
        assertTrue(d.isNone());
    }

    @Test
    public void upwardVerticalGestureMapsToUp() {
        GestureDecision d = GestureClassifier.classify(
                session(10f, -80f), 12f, 1.2f, 100f, 120f);
        assertEquals(GestureCode.UP, d.code());
        assertFalse(d.longTier());
    }

    @Test
    public void downwardGestureKeepsShortAndLongTiers() {
        GestureDecision shortDown = GestureClassifier.classify(
                session(5f, 70f), 12f, 1.2f, 100f, 120f);
        GestureDecision longDown = GestureClassifier.classify(
                session(5f, 130f), 12f, 1.2f, 100f, 120f);

        assertEquals(GestureCode.DOWN, shortDown.code());
        assertFalse(shortDown.longTier());
        assertEquals(GestureCode.DOWN, longDown.code());
        assertTrue(longDown.longTier());
    }

    @Test
    public void sideGestureKeepsShortAndLongCodes() {
        GestureDecision shortSide = GestureClassifier.classify(
                session(80f, 8f), 12f, 1.2f, 100f, 120f);
        GestureDecision longSide = GestureClassifier.classify(
                session(150f, 8f), 12f, 1.2f, 100f, 120f);

        assertEquals(GestureCode.SIDE_SHORT, shortSide.code());
        assertFalse(shortSide.longTier());
        assertEquals(GestureCode.SIDE_LONG, longSide.code());
        assertTrue(longSide.longTier());
    }

    @Test
    public void travelledExtentCountsEvenWhenPointerReturnsNearOrigin() {
        GestureSession s = new GestureSession();
        s.begin(0f, 0f, 0L);
        s.add(140f, 0f, 10L);
        s.add(5f, 0f, 20L);

        GestureDecision d = GestureClassifier.classify(s, 12f, 1.2f, 100f, 120f);

        assertEquals(GestureCode.SIDE_LONG, d.code());
        assertTrue(d.longTier());
        assertEquals(140f, d.extentX(), 0.001f);
    }
}
