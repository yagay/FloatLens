package com.yagay.floatlens.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleLensViewportHookTest {
    @Test public void earlySetupIsAllowedUntilFrozenImageIsReady() {
        assertFalse(GoogleLensViewportHook.shouldSuppressViewportState(
                true, false, false));
    }

    @Test public void fullScreenFrozenImageLocksViewportBeforeSelection() {
        assertTrue(GoogleLensViewportHook.shouldSuppressViewportState(
                true, false, true));
    }

    @Test public void regionSelectionAlwaysLocksViewport() {
        assertTrue(GoogleLensViewportHook.shouldSuppressViewportState(
                true, true, false));
    }

    @Test public void inactiveSessionNeverSuppressesNativeGoogleViewport() {
        assertFalse(GoogleLensViewportHook.shouldSuppressViewportState(
                false, true, true));
    }
}
