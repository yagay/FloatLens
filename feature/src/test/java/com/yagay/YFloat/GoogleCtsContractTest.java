package com.yagay.YFloat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleCtsContractTest {
    @Test public void markerRequiresTriggerAndToken() {
        assertFalse(GoogleCtsContract.isYFloatSession(false, ""));
        assertFalse(GoogleCtsContract.isYFloatSession(true, ""));
        assertFalse(GoogleCtsContract.isYFloatSession(true, null));
        assertTrue(GoogleCtsContract.isYFloatSession(true, "abc"));
    }

    @Test public void contextualSearchBoundaryMatchesOnlyPlatformAction() {
        assertTrue(GoogleCtsContract.isContextualSearchAction(
                "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"));
        assertFalse(GoogleCtsContract.isContextualSearchAction(null));
        assertFalse(GoogleCtsContract.isContextualSearchAction(
                "android.intent.action.WEB_SEARCH"));
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
