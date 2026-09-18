package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleCtsContractTest {
    @Test public void markerRequiresTriggerAndToken() {
        assertFalse(GoogleCtsContract.isFloatLensSession(false, ""));
        assertFalse(GoogleCtsContract.isFloatLensSession(true, ""));
        assertFalse(GoogleCtsContract.isFloatLensSession(true, null));
        assertTrue(GoogleCtsContract.isFloatLensSession(true, "abc"));
    }

    @Test public void traceAuthorizationRequiresMatchingLiveSession() {
        long now = 1000L;
        assertTrue(GoogleCtsContract.isAuthorizedTrace("token", now + 1000L, "token", now));
        assertFalse(GoogleCtsContract.isAuthorizedTrace("token", now - 1L, "token", now));
        assertFalse(GoogleCtsContract.isAuthorizedTrace("token", now + 1000L, "other", now));
        assertFalse(GoogleCtsContract.isAuthorizedTrace("", now + 1000L, "", now));
        assertFalse(GoogleCtsContract.isAuthorizedTrace(
                "token", now + GoogleCtsContract.TRACE_SESSION_TTL_MS + 1L, "token", now));
    }
}
