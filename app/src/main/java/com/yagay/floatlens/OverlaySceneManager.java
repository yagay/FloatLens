package com.yagay.floatlens;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow-scoped owner for transient overlay windows.
 *
 * <p>Tracking/selection overlays bind to the active workflow and are released together when that
 * workflow finishes, is cancelled, fails or is replaced. Result surfaces such as action menus are
 * intentionally not bound and can outlive capture/selection handoff.</p>
 */
final class OverlaySceneManager {
    private static final Object LOCK = new Object();
    private static final Map<Long, Scene> SCENES = new LinkedHashMap<>();

    private static final class Scene {
        final long workflowId;
        final IdentityHashMap<Object, Runnable> cleanup = new IdentityHashMap<>();

        Scene(long workflowId) {
            this.workflowId = workflowId;
        }
    }

    static void open(long workflowId) {
        if (workflowId <= 0L) return;
        synchronized (LOCK) {
            SCENES.computeIfAbsent(workflowId, Scene::new);
        }
    }

    static void bindCurrent(Object identity, Runnable cleanup) {
        if (identity == null || cleanup == null) return;
        long workflowId = WorkflowSessionManager.currentId();
        if (workflowId <= 0L) return;
        synchronized (LOCK) {
            SCENES.computeIfAbsent(workflowId, Scene::new).cleanup.put(identity, cleanup);
        }
    }

    static void unbind(Object identity) {
        if (identity == null) return;
        synchronized (LOCK) {
            for (Scene scene : SCENES.values()) scene.cleanup.remove(identity);
        }
    }

    static int close(long workflowId, String reason) {
        if (workflowId <= 0L) return 0;
        Scene scene;
        synchronized (LOCK) {
            scene = SCENES.remove(workflowId);
        }
        if (scene == null) return 0;

        List<Runnable> callbacks = new ArrayList<>(scene.cleanup.values());
        scene.cleanup.clear();
        int released = 0;
        for (Runnable callback : callbacks) {
            try {
                callback.run();
                released++;
            } catch (Throwable ignored) { }
        }
        return released;
    }

    static int sceneCount() {
        synchronized (LOCK) { return SCENES.size(); }
    }

    static int bindingCount(long workflowId) {
        synchronized (LOCK) {
            Scene scene = SCENES.get(workflowId);
            return scene == null ? 0 : scene.cleanup.size();
        }
    }

    static boolean shouldBindWindow(String tag) {
        if (tag == null || tag.isBlank()) return true;
        String normalized = tag.toLowerCase(java.util.Locale.ROOT);
        // Persistent service surfaces and result/action menus intentionally outlive a workflow.
        if (normalized.startsWith("float_icon")
                || normalized.contains("action_menu")
                || normalized.contains("edge_wake")) {
            return false;
        }
        return true;
    }

    private OverlaySceneManager() {}
}
