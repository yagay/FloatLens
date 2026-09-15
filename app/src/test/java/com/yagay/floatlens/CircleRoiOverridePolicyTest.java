package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CircleRoiOverridePolicyTest {
    @Test public void centerInsideRegionOverridesLine() {
        assertTrue(CircleRoiOverridePolicy.materiallyCovered(
                10, 10, 80, 50,
                0, 0, 100, 60));
    }

    @Test public void meaningfulOverlapOverridesEvenWhenCenterOutside() {
        assertTrue(CircleRoiOverridePolicy.materiallyCovered(
                0, 0, 100, 100,
                0, 0, 30, 100));
    }

    @Test public void tinyOverlapDoesNotOverride() {
        assertFalse(CircleRoiOverridePolicy.materiallyCovered(
                0, 0, 100, 100,
                95, 95, 120, 120));
    }

    @Test public void separatedRectsDoNotIntersect() {
        assertFalse(CircleRoiOverridePolicy.intersectsOrContains(
                0, 0, 20, 20,
                30, 30, 50, 50));
    }
}
