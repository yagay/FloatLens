package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Entry point for the local Circle Select workspace. */
public final class CircleSelectController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService VIEW_SNAPSHOT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-view-snapshot");
        t.setDaemon(true);
        return t;
    });

    private static long generation;
    private static Future<?> snapshotFuture;
    private static ScreenshotHideCoordinator.Lease pendingHideLease;

    public static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;
        cancelPendingLocked(app, "restart");
        CircleSelectOverlay.dismissActive("restart");
        CircleActiveBorderOverlay.hide(app, "restart");

        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "circle_select");
        final ScreenshotHideCoordinator.Lease hideLease =
                ScreenshotHideCoordinator.acquire(app, "circle_select_" + gen);
        pendingHideLease = hideLease;

        FloatService service = FloatService.get();
        if (service != null) service.onCircleCaptureStarted();

        FloatSettings settings = new FloatSettings(app);
        int ocrMode = CircleOcrPolicy.mode(settings);
        boolean needView = CircleOcrPolicy.viewEnabled(settings);
        DiagnosticLog.i(app, "CIRCLE_SELECT", "recognition mode=" + ocrMode
                + " view=" + needView
                + " mlkit=" + CircleOcrPolicy.mlKitEnabled(settings)
                + " ppocr=false gen=" + gen);

        if (!needView) {
            CircleViewTextSnapshot empty = CircleViewTextSnapshot.empty(ScreenGeometry.displayBounds(app));
            MAIN.post(() -> captureAndShow(app, service, hideLease, shadeState, empty, gen));
            return;
        }

        DiagnosticLog.i(app, "CIRCLE_SELECT", "view snapshot begin gen=" + gen
                + " shadeExpanded=" + shadeState.expandedAtCapture());

        snapshotFuture = VIEW_SNAPSHOT_IO.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            long started = android.os.SystemClock.uptimeMillis();
            CircleViewTextSnapshot snapshot = CircleViewTextSnapshot.capture(app);
            if (Thread.currentThread().isInterrupted()) return;
            long elapsed = android.os.SystemClock.uptimeMillis() - started;
            MAIN.post(() -> {
                synchronized (CircleSelectController.class) {
                    if (gen != generation) return;
                    snapshotFuture = null;
                }
                DiagnosticLog.i(app, "CIRCLE_SELECT", "view snapshot ready gen=" + gen
                        + " nodes=" + snapshot.nodeCount()
                        + " exactGeometry=" + snapshot.exactGeometryNodeCount()
                        + " elapsedMs=" + elapsed);
                MAIN.postDelayed(() -> captureAndShow(app, service, hideLease,
                        shadeState, snapshot, gen), 24L);
            });
        });
    }

    private static void captureAndShow(Context app, FloatService service,
                                       ScreenshotHideCoordinator.Lease hideLease,
                                       FlSystemPanelController.CaptureState shadeState,
                                       CircleViewTextSnapshot snapshot, long gen) {
        synchronized (CircleSelectController.class) {
            if (gen != generation) {
                hideLease.release(app);
                return;
            }
        }
        DiagnosticLog.i(app, "CIRCLE_SELECT", "capture begin gen=" + gen
                + " viewNodes=" + snapshot.nodeCount());
        CircleSelectFrame.capture(app, bitmap -> {
            synchronized (CircleSelectController.class) {
                if (gen != generation) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    hideLease.release(app);
                    return;
                }
            }
            if (bitmap == null || bitmap.isRecycled()) {
                restore(app, service, hideLease, gen, "invalid_capture");
                return;
            }

            boolean shown = CircleSelectOverlay.show(app, bitmap, snapshot,
                    () -> restore(app, service, hideLease, gen, "closed"));
            DiagnosticLog.i(app, "CIRCLE_SELECT", "accessibility workspace shown=" + shown
                    + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " viewNodes=" + snapshot.nodeCount());
            if (!shown) {
                if (!bitmap.isRecycled()) bitmap.recycle();
                restore(app, service, hideLease, gen, "overlay_failed");
                Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
                return;
            }

            CircleActiveBorderOverlay.show(app);

            OverlayShadeCoordinator.cleanup(app, shadeState.expandedAtCapture(), "circle_select",
                    collapsed -> {
                        synchronized (CircleSelectController.class) {
                            if (gen != generation) return;
                        }
                        DiagnosticLog.i(app, "CIRCLE_SELECT", "background shade cleanup collapsed="
                                + collapsed + " gen=" + gen);
                        CircleSelectOverlay.promoteActiveFocus(
                                collapsed ? "shade_collapsed" : "shade_cleanup_exhausted");
                    });
        }, error -> {
            synchronized (CircleSelectController.class) {
                if (gen != generation) {
                    hideLease.release(app);
                    DiagnosticLog.i(app, "CIRCLE_SELECT", "drop stale capture failure gen=" + gen
                            + " current=" + generation + " error=" + safe(error));
                    return;
                }
            }
            restore(app, service, hideLease, gen, "capture_failed");
            Toast.makeText(app, "圈画识别截图失败: " + safe(error), Toast.LENGTH_LONG).show();
            DiagnosticLog.i(app, "CIRCLE_SELECT", "capture failed=" + safe(error));
        });
    }

    private static void restore(Context app, FloatService service,
                                ScreenshotHideCoordinator.Lease hideLease,
                                long gen, String reason) {
        hideLease.release(app);
        synchronized (CircleSelectController.class) {
            if (pendingHideLease == hideLease) pendingHideLease = null;
            if (gen != generation) return;
        }
        CircleActiveBorderOverlay.hide(app, "circle_select_" + reason);
        if (service != null) service.onCircleFinished("circle_select_" + reason);
    }

    private static void cancelPendingLocked(Context app, String reason) {
        Future<?> future = snapshotFuture;
        snapshotFuture = null;
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            DiagnosticLog.i(app, "CIRCLE_SELECT", "cancel view snapshot reason=" + reason
                    + " success=" + cancelled);
        }
        ScreenshotHideCoordinator.Lease lease = pendingHideLease;
        pendingHideLease = null;
        if (lease != null) lease.release(app);
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private CircleSelectController() {}
}
