package com.yagay.floatlens;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GoogleAppControllerTest {
    @Test public void parseRootStatePrefersFrozenThenRunningThenStopped() {
        assertEquals(GoogleAppController.State.FROZEN,
                GoogleAppController.parseState(
                        "noise\nFLOATLENS_GOOGLE_STATE user=0 disabled=1 running=0 stopped=1"));
        assertEquals(GoogleAppController.State.RUNNING,
                GoogleAppController.parseState(
                        "FLOATLENS_GOOGLE_STATE user=0 disabled=0 running=1 stopped=0"));
        assertEquals(GoogleAppController.State.STOPPED,
                GoogleAppController.parseState(
                        "FLOATLENS_GOOGLE_STATE user=0 disabled=0 running=0 stopped=1"));
    }

    @Test public void enabledButIdleIsReportedStopped() {
        assertEquals(GoogleAppController.State.STOPPED,
                GoogleAppController.parseState(
                        "FLOATLENS_GOOGLE_STATE user=0 disabled=0 running=0 stopped=0"));
    }

    @Test public void malformedOutputIsUnknown() {
        assertEquals(GoogleAppController.State.UNKNOWN,
                GoogleAppController.parseState(""));
        assertEquals(GoogleAppController.State.UNKNOWN,
                GoogleAppController.parseState("permission denied"));
    }
}
