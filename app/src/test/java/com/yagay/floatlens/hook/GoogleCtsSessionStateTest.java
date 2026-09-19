package com.yagay.floatlens.hook;

import static org.junit.Assert.*;

import org.junit.Test;

public class GoogleCtsSessionStateTest {
    @Test public void beginCreatesSingleGenerationAndOldCallbacksStopMatching() {
        GoogleCtsSessionState state = new GoogleCtsSessionState();
        long first = state.begin("one", 3, 1000L);
        assertTrue(state.matches(first, "one"));

        long second = state.begin("two", 4, 2000L);
        assertFalse(state.matches(first, "one"));
        assertTrue(state.matches(second, "two"));
        assertEquals(4, state.showSessionId());
    }

    @Test public void regionSelectionRequiresBoundsBeforeConfirm() {
        GoogleCtsSessionState state = new GoogleCtsSessionState();
        state.begin("token", 1, 1000L);
        state.onSelection("", null, true);
        assertFalse(state.canConfirm("token"));

        state.onSelection("", new GoogleCtsSessionState.Bounds(10, 20, 100, 200), true);
        assertTrue(state.canConfirm("token"));
        assertEquals(GoogleCtsSessionState.Phase.CONFIRM_PENDING, state.phase());
    }

    @Test public void committedSessionRejectsOldMutationsAfterFinish() {
        GoogleCtsSessionState state = new GoogleCtsSessionState();
        long generation = state.begin("token", 1, 1000L);
        state.onSelection("abc",
                new GoogleCtsSessionState.Bounds(1, 2, 3, 4), false);
        assertTrue(state.markCommitted("token"));
        assertEquals(GoogleCtsSessionState.Phase.COMMITTED, state.phase());

        state.finish();
        assertFalse(state.matches(generation, "token"));
        state.onSelection("late",
                new GoogleCtsSessionState.Bounds(4, 5, 6, 7), false);
        assertEquals("abc", state.selectionText());
    }

    @Test public void extendKeepsGenerationAndIdentity() {
        GoogleCtsSessionState state = new GoogleCtsSessionState();
        long generation = state.begin("token", 1, 1000L);
        state.extend(2000L);
        state.setShowSessionId(9);
        assertEquals(generation, state.generation());
        assertEquals("token", state.token());
        assertEquals(9, state.showSessionId());
        assertEquals(2000L, state.activeUntil());
    }
}
