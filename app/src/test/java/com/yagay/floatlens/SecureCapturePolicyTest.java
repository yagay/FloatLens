package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression coverage for the time-bounded secure capture policy. */
public class SecureCapturePolicyTest {
    @Test public void leaseAtBoundaryIsExpired() {
        long now = 50_000L;
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(
                true, true, true, now, now));
    }

    @Test public void oneMillisecondRemainingIsStillActive() {
        long now = 50_000L;
        assertTrue(LsposedRuntimeConfig.isSecureCaptureActive(
                true, true, true, now + 1L, now));
    }

    @Test public void disabledFeatureCannotBeArmedByTimestampAlone() {
        long now = 50_000L;
        assertFalse(LsposedRuntimeConfig.isSecureCaptureActive(
                true, true, false, now + 2_000L, now));
    }
}
