package com.yagay.floatlens;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class OverlaySceneManagerTest {
    @Test public void workflowEndReleasesBoundOverlaysExactlyOnce() {
        WorkflowSessionManager.Session session = WorkflowSessionManager.begin(
                null, WorkflowSessionManager.Type.CIRCLE, "scene_test");
        AtomicInteger released = new AtomicInteger();

        Object first = new Object();
        Object second = new Object();
        OverlaySceneManager.bindCurrent(first, released::incrementAndGet);
        OverlaySceneManager.bindCurrent(second, released::incrementAndGet);
        assertEquals(2, OverlaySceneManager.bindingCount(session.id()));

        WorkflowSessionManager.finish(null, session, "done");
        assertEquals(2, released.get());
        assertEquals(0, OverlaySceneManager.bindingCount(session.id()));
    }

    @Test public void manuallyRemovedOverlayDoesNotReleaseAgain() {
        WorkflowSessionManager.Session session = WorkflowSessionManager.begin(
                null, WorkflowSessionManager.Type.VIEW, "scene_unbind");
        AtomicInteger released = new AtomicInteger();
        Object view = new Object();
        OverlaySceneManager.bindCurrent(view, released::incrementAndGet);
        OverlaySceneManager.unbind(view);

        WorkflowSessionManager.finish(null, session, "done");
        assertEquals(0, released.get());
    }

    @Test public void resultMenusAndPersistentIconsAreNotWorkflowBound() {
        assertEquals(false, OverlaySceneManager.shouldBindWindow("float_action_menu"));
        assertEquals(false, OverlaySceneManager.shouldBindWindow("image_action_menu"));
        assertEquals(false, OverlaySceneManager.shouldBindWindow("float_icon"));
        assertEquals(true, OverlaySceneManager.shouldBindWindow("google_region_confirm"));
        assertEquals(true, OverlaySceneManager.shouldBindWindow("probe"));
    }
}
