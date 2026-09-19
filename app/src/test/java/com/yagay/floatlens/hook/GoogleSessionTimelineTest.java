package com.yagay.floatlens.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleSessionTimelineTest {
    @Test public void summaryTracksPhasesAndNoViolationForNormalFlow() {
        GoogleSessionTimeline timeline = new GoogleSessionTimeline();
        timeline.reset(3L, "abcdef123456");
        timeline.record(1, "FRAME", GoogleCtsSessionState.Phase.FRAME_READY, "ok");
        timeline.record(2, "USER_SELECTION", GoogleCtsSessionState.Phase.SELECTING, "ok");
        timeline.record(3, "REGION_SELECTION_CONFIRMED",
                GoogleCtsSessionState.Phase.CONFIRM_PENDING, "ok");
        assertEquals(0, timeline.violationCount());
        String summary = timeline.summary();
        assertTrue(summary.contains("generation=3"));
        assertTrue(summary.contains("events=3"));
        assertTrue(summary.contains("violations=0"));
    }

    @Test public void terminalEventIsFlagged() {
        GoogleSessionTimeline timeline = new GoogleSessionTimeline();
        timeline.reset(1L, "token");
        timeline.record(1, "LATE", GoogleCtsSessionState.Phase.FINISHED, "late");
        assertEquals(1, timeline.violationCount());
    }
}
