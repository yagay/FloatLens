package com.yagay.floatlens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FloatingPositionMathTest {
    @Test public void sideIsStableAcrossPortraitAndLandscapeProjection() {
        assertEquals(1032, FloatingPositionMath.edgeX(1, 1080, 48));
        assertEquals(2352, FloatingPositionMath.edgeX(1, 2400, 48));
        assertEquals(0, FloatingPositionMath.edgeX(0, 1080, 48));
        assertEquals(0, FloatingPositionMath.edgeX(0, 2400, 48));
    }

    @Test public void normalizedYRoundTripsAcrossDifferentHeights() {
        int bp = FloatingPositionMath.basisPointsFromY(600, 1800, 3333);
        assertTrue(Math.abs(bp - 3333) <= 1);
        assertTrue(Math.abs(FloatingPositionMath.yFromBasisPoints(bp, 2700) - 900) <= 1);
    }

    @Test public void hiddenEdgeUsesSameSideWithoutReclassification() {
        assertEquals(-14, FloatingPositionMath.hiddenEdgeX(true, 1080, 50, 28));
        assertEquals(1044, FloatingPositionMath.hiddenEdgeX(false, 1080, 50, 28));
    }
}
