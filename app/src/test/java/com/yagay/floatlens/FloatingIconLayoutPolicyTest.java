package com.yagay.floatlens;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FloatingIconLayoutPolicyTest {
    @Test public void portraitRightXIsNotReinterpretedAsLandscapeLeft() {
        // 1219 is the right-edge coordinate on a 1272px portrait display, but sits left of the
        // midpoint on a 2772px landscape display. With no landscape-specific X, side semantics
        // must win over that old absolute coordinate.
        assertEquals(1, FloatingIconLayoutPolicy.resolveRestoreSide(
                1219, 143, 2772,
                false, true, true, 1));
    }

    @Test public void orientationSpecificCoordinateStillDeterminesItsOwnSide() {
        assertEquals(0, FloatingIconLayoutPolicy.resolveRestoreSide(
                -90, 143, 1272,
                true, true, true, 1));
        assertEquals(1, FloatingIconLayoutPolicy.resolveRestoreSide(
                1219, 143, 1272,
                true, true, true, 0));
    }

    @Test public void legacyCoordinateIsTrustedOnlyWhenClearlyAtAnEdge() {
        assertEquals(0, FloatingIconLayoutPolicy.resolveRestoreSide(
                -90, 143, 2772,
                false, true, false, 1));
        assertEquals(1, FloatingIconLayoutPolicy.resolveRestoreSide(
                1219, 143, 2772,
                false, true, false, 0));
    }
}
