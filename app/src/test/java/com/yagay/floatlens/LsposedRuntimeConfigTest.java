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

    @Test public void googleCtsFallbackInvocationMatchesObservedMetadata() {
        long trigger = 50_000L;
        assertTrue(LsposedRuntimeConfig.matchesGoogleCtsFallbackInvocation(
                trigger, true, trigger, true,
                LsposedRuntimeConfig.GOOGLE_CTS_EXPECTED_OMNI_ENTRY_POINT));
        assertTrue(LsposedRuntimeConfig.matchesGoogleCtsFallbackInvocation(
                trigger, false, -1L, false, -1));
        assertFalse(LsposedRuntimeConfig.matchesGoogleCtsFallbackInvocation(
                trigger, true,
                trigger + LsposedRuntimeConfig.GOOGLE_CTS_INVOCATION_MATCH_TOLERANCE_MS + 1L,
                true, LsposedRuntimeConfig.GOOGLE_CTS_EXPECTED_OMNI_ENTRY_POINT));
        assertFalse(LsposedRuntimeConfig.matchesGoogleCtsFallbackInvocation(
                trigger, true, trigger, true,
                LsposedRuntimeConfig.GOOGLE_CTS_EXPECTED_OMNI_ENTRY_POINT + 1));
    }

    @Test public void googleRegionConfirmIsTokenBoundAndShortLived() {
        long now = 80_000L;
        assertTrue(LsposedRuntimeConfig.isGoogleRegionConfirmRequested(
                "session", "session", now - 100L, now));
        assertFalse(LsposedRuntimeConfig.isGoogleRegionConfirmRequested(
                "session", "other", now - 100L, now));
        assertFalse(LsposedRuntimeConfig.isGoogleRegionConfirmRequested(
                "session", "session",
                now - LsposedRuntimeConfig.GOOGLE_CTS_REGION_CONFIRM_TTL_MS - 1L, now));
        assertFalse(LsposedRuntimeConfig.isGoogleRegionConfirmRequested(
                "", "", now - 100L, now));
    }

    @Test public void googleCtsFallbackLeaseIsShortAndTokenBound() {
        long now = 10_000L;
        assertTrue(LsposedRuntimeConfig.isGoogleCtsFallbackArmed(
                true, "abc", now - 100L, now + 1000L, now));
        assertFalse(LsposedRuntimeConfig.isGoogleCtsFallbackArmed(
                true, "", now - 100L, now + 1000L, now));
        assertFalse(LsposedRuntimeConfig.isGoogleCtsFallbackArmed(
                true, "abc", now - LsposedRuntimeConfig.GOOGLE_CTS_FALLBACK_WINDOW_MS - 1L,
                now + 1000L, now));
        assertFalse(LsposedRuntimeConfig.isGoogleCtsFallbackArmed(
                true, "abc", now - 100L, now - 1L, now));
    }
}
