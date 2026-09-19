package com.yagay.floatlens;

import static org.junit.Assert.*;

import org.junit.Test;

public class WorkflowSessionManagerTest {
    @Test public void beginReplacesPreviousAndKeepsSingleCurrentIdentity() {
        WorkflowSessionManager.Session first = WorkflowSessionManager.begin(
                null, WorkflowSessionManager.Type.CIRCLE, "first");
        assertTrue(first.current());

        WorkflowSessionManager.Session second = WorkflowSessionManager.begin(
                null, WorkflowSessionManager.Type.OCR, "second");
        assertFalse(first.current());
        assertEquals(WorkflowSessionManager.Phase.CANCELLED, first.phase());
        assertTrue(second.current());
        assertNotEquals(first.id(), second.id());

        WorkflowSessionManager.finish(null, second, "done");
        assertFalse(second.current());
        assertNull(WorkflowSessionManager.current());
    }

    @Test public void externalIdentityMatchesOnlyCurrentSession() {
        WorkflowSessionManager.Session session = WorkflowSessionManager.beginExternal(
                null, WorkflowSessionManager.Type.GOOGLE_CTS, "token-1", "google");
        assertTrue(WorkflowSessionManager.matchesExternal("token-1"));
        assertFalse(WorkflowSessionManager.matchesExternal("token-2"));

        WorkflowSessionManager.finishExternal(null, "token-1", "done");
        assertNull(WorkflowSessionManager.current());
        assertEquals(WorkflowSessionManager.Phase.FINISHED, session.phase());
    }
}
