package com.yagay.floatlens;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FV-style prepared Accessibility snapshot.
 *
 * Accessibility traversal is deliberately kept out of the MotionEvent MOVE path. Accessibility
 * events schedule/coalesce a background rebuild; direct selection only reads an immutable geometry
 * snapshot and performs cheap contains(x,y) hit tests.
 */
final class AccessibilityCandidateCache {
    private static final long EVENT_COALESCE_MS = 70L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-accessibility-cache");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean scheduled = new AtomicBoolean();
    private static final AtomicBoolean running = new AtomicBoolean();

    private static volatile WeakReference<LensAccessibilityService> serviceRef = new WeakReference<>(null);
    private static volatile String pendingReason = "";
    private static volatile boolean dirtyWhileRunning;
    private static volatile List<ScreenCandidate> snapshot = Collections.emptyList();
    private static volatile long version;

    private AccessibilityCandidateCache() {}

    static List<ScreenCandidate> snapshot() {
        return snapshot;
    }

    static long version() {
        return version;
    }

    static void requestRefresh(LensAccessibilityService service, String reason) {
        requestRefresh(service, reason, EVENT_COALESCE_MS);
    }

    static void requestRefresh(LensAccessibilityService service, String reason, long delayMs) {
        if (service == null) return;
        serviceRef = new WeakReference<>(service);
        pendingReason = reason == null ? "" : reason;
        if (running.get()) dirtyWhileRunning = true;
        if (!scheduled.compareAndSet(false, true)) return;
        MAIN.postDelayed(AccessibilityCandidateCache::startRefresh, Math.max(0L, delayMs));
    }

    private static void startRefresh() {
        scheduled.set(false);
        LensAccessibilityService service = serviceRef.get();
        if (service == null) return;
        if (!running.compareAndSet(false, true)) {
            dirtyWhileRunning = true;
            requestRefresh(service, "coalesced_while_running", EVENT_COALESCE_MS);
            return;
        }

        final String reason = pendingReason;
        EXECUTOR.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            List<ScreenCandidate> next = Collections.emptyList();
            Throwable failure = null;
            try {
                List<ScreenCandidate> collected = AccessibilityCandidateCollector.collect(service);
                if (collected != null) next = List.copyOf(collected);
            } catch (Throwable t) {
                failure = t;
            }

            if (failure == null) {
                snapshot = next;
                version++;
                DiagnosticLog.i(service, "FL_TREE_CACHE", "EVENT_READY version=" + version
                        + " total=" + next.size()
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                        + " reason=" + reason);
            } else {
                DiagnosticLog.i(service, "FL_TREE_CACHE", "EVENT_FAILED elapsedMs="
                        + (SystemClock.elapsedRealtime() - started)
                        + " reason=" + reason + " error=" + failure);
            }

            running.set(false);
            if (dirtyWhileRunning) {
                dirtyWhileRunning = false;
                LensAccessibilityService current = serviceRef.get();
                if (current != null) requestRefresh(current, "dirty_after_refresh", EVENT_COALESCE_MS);
            }
        });
    }

    static void clear() {
        MAIN.removeCallbacksAndMessages(null);
        scheduled.set(false);
        dirtyWhileRunning = false;
        snapshot = Collections.emptyList();
        version++;
        serviceRef = new WeakReference<>(null);
    }
}
