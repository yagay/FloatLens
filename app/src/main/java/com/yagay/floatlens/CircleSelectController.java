package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Entry point for the Google-like local Circle Select workspace. */
public final class CircleSelectController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService VIEW_SNAPSHOT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-view-snapshot");
        t.setDaemon(true);
        return t;
    });
    private static long generation;

    public static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;
        CircleSelectOverlay.dismissActive("restart");
        CircleActiveBorderOverlay.hide(app, "restart");
        CircleViewTextSnapshotHandoff.clear();

        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "circle_select");
        final ScreenshotHideCoordinator.Lease hideLease =
                ScreenshotHideCoordinator.acquire(app, "circle_select_" + gen);

        FloatService service = FloatService.get();
        if (service != null) service.onCircleCaptureStarted();

        DiagnosticLog.i(app, "CIRCLE_SELECT", "view snapshot begin gen=" + gen
                + " shadeExpanded=" + shadeState.expandedAtCapture());

        // Freeze the target app's View text before our AccessibilityOverlay exists. The scan runs
        // off the main thread, then screenshot capture starts immediately afterwards so geometry
        // remains close to the frozen frame while avoiding a long UI-thread tree walk.
        VIEW_SNAPSHOT_IO.execute(() -> {
            long started = android.os.SystemClock.uptimeMillis();
            CircleViewTextSnapshot snapshot = CircleViewTextSnapshot.capture(app);
            long elapsed = android.os.SystemClock.uptimeMillis() - started;
            MAIN.post(() -> {
                synchronized (CircleSelectController.class) {
                    if (gen != generation) {
                        hideLease.release(app);
                        return;
                    }
                }
                CircleViewTextSnapshotHandoff.publish(snapshot);
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
                    CircleViewTextSnapshotHandoff.clear();
                    return;
                }
            }
            if (bitmap == null || bitmap.isRecycled()) {
                restore(app, service, hideLease, gen, "invalid_capture");
                return;
            }

            boolean shown = CircleSelectOverlay.show(app, bitmap,
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

            // The frozen bitmap already exists at this point, so the active-state border can never
            // become part of Circle Select's own screenshot/crop result.
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
                    CircleViewTextSnapshotHandoff.clear();
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
        CircleViewTextSnapshotHandoff.clear();
        synchronized (CircleSelectController.class) {
            if (gen != generation) return;
        }
        CircleActiveBorderOverlay.hide(app, "circle_select_" + reason);
        if (service != null) service.onCircleFinished("circle_select_" + reason);
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private CircleSelectController() {}
}
