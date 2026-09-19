package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleCtsBridgeControllerTest {
    @Test public void sameSizeTransportFrameCannotOverwriteCanonicalFrame() {
        assertFalse(GoogleCtsBridgeController.shouldReplaceCanonicalFrame(
                1272, 2772, 1272, 2772));
    }

    @Test public void smallerTransportFrameCannotOverwriteCanonicalFrame() {
        assertFalse(GoogleCtsBridgeController.shouldReplaceCanonicalFrame(
                1272, 2772, 1080, 2340));
    }

    @Test public void largerTransportFrameMayReplaceIncompleteCanonicalFrame() {
        assertTrue(GoogleCtsBridgeController.shouldReplaceCanonicalFrame(
                720, 1568, 1272, 2772));
    }
}
