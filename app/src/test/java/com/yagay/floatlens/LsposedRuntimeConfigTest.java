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

    @Test public void secureCaptureRequiresEveryGateAndLiveLease() {
        long now = 100_000L;
        long live = now + 2_000L;
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(false, true, true, live, now));
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(true, false, true, live, now));
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(true, true, false, live, now));
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(true, true, true, now, now));
        assertTrue(LsposedRuntimeConfig.isSecureCaptureActive(true, true, true, live, now));
    }

    @Test public void secureCaptureRejectsImplausiblyLongFutureLease() {
        long now = 200_000L;
        long staleFuture = now + LsposedRuntimeConfig.MAX_VALID_SECURE_CAPTURE_FUTURE_MS + 1L;
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(
                true, true, true, staleFuture, now));
    }
}
