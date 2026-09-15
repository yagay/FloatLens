package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SecureCaptureProbePolicyTest {
    @Test
    public void markerColorAcceptsSmallCaptureVariation() {
        assertTrue(SecureCaptureProbePolicy.isMarkerColor(37, 199, 83));
        assertTrue(SecureCaptureProbePolicy.isMarkerColor(55, 180, 101));
        assertFalse(SecureCaptureProbePolicy.isMarkerColor(0, 0, 0));
    }

    @Test
    public void successRequiresSixtyPercentMatches() {
        assertTrue(SecureCaptureProbePolicy.isSuccessful(15, 25));
        assertFalse(SecureCaptureProbePolicy.isSuccessful(14, 25));
        assertFalse(SecureCaptureProbePolicy.isSuccessful(0, 0));
    }
}
