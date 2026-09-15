package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LsposedRuntimeConfigTest {
    @Test public void providerRequiresBothSwitches() {
        assertFalse(LsposedRuntimeConfig.isEnabled(false, false));
        assertFalse(LsposedRuntimeConfig.isEnabled(true, false));
        assertFalse(LsposedRuntimeConfig.isEnabled(false, true));
        assertTrue(LsposedRuntimeConfig.isEnabled(true, true));
    }

    @Test public void secureCaptureRequiresEveryGateAndUnexpiredWindow() {
        long now = 10_000L;
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(false, true, true, now + 1, now));
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(true, false, true, now + 1, now));
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(true, true, false, now + 1, now));
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(true, true, true, now, now));
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(true, true, true, now - 1, now));
        assertTrue(LsposedRuntimeConfig.isSecureCaptureActive(true, true, true, now + 1, now));
    }
}
