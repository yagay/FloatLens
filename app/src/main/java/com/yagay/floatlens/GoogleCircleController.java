package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Entry point for the new Google-style content-selection workflow. Legacy CircleSelect is isolated. */
final class GoogleCircleController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService CONTENT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-google-circle-content");
        t.setDaemon(true);
        return t;
    });

    private static long generation;
    private static Future<?> contentFuture;
    private static ScreenshotHideCoordinator.Lease pendingHideLease;

    static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;

        GoogleCircleOverlay.dismissActive("restart");
        cancelPendingLocked(app, "restart");

        FlSystemPanelController.CaptureState shadeState =
                FlSystemPanelController.beginCapture(app, "google_circle");
        ScreenshotHideCoordinator.Lease hideLease =
                ScreenshotHideCoordinator.acquire(app, "google_circle_" + gen);
        pendingHideLease = hideLease;
        DiagnosticLog.i(app, "G_CIRCLE", "start gen=" + gen
                + " phase=semantic_snapshot");

        contentFuture = CONTENT_IO.submit(() -> {
            long started = android.os.SystemClock.uptimeMillis();
            GoogleCircleContentSnapshot content = GoogleCircleContentSnapshot.capture(app);
            if (Thread.currentThread().isInterrupted()) return;
            long elapsed = android.os.SystemClock.uptimeMillis() - started;
            MAIN.post(() -> {
                synchronized (GoogleCircleController.class) {
                    if (gen != generation) return;
                    contentFuture = null;
                }
                DiagnosticLog.i(app, "G_CIRCLE", "semantic snapshot ready gen=" + gen
                        + " text=" + content.textCount()
                        + " images=" + content.imageCount()
                        + " elapsedMs=" + elapsed);
                captureAndShow(app, hideLease, shadeState, content, gen);
            });
        });
    }

    private static void captureAndShow(Context app,
                                       ScreenshotHideCoordinator.Lease hideLease,
                                       FlSystemPanelController.CaptureState shadeState,
                                       GoogleCircleContentSnapshot content,
                                       long gen) {
        synchronized (GoogleCircleController.class) {
            if (gen != generation) {
                hideLease.release(app);
                return;
            }
        }
        GoogleCircleCapture.capture(app, frame -> {
            synchronized (GoogleCircleController.class) {
                if (gen != generation) {
                    frame.recycle();
                    hideLease.release(app);
                    return;
                }
            }
            boolean shown = GoogleCircleOverlay.show(app, frame, content,
                    () -> restore(app, hideLease, gen, "closed"));
            if (!shown) {
                frame.recycle();
                restore(app, hideLease, gen, "overlay_failed");
                Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
                return;
            }

            FlSystemPanelController.onOverlayReady(app, shadeState, "google_circle",
                    collapsed -> {
                        synchronized (GoogleCircleController.class) {
                            if (gen != generation) return;
                        }
                        DiagnosticLog.i(app, "G_CIRCLE", "shade cleanup collapsed="
                                + collapsed + " gen=" + gen);
                        GoogleCircleOverlay.promoteActiveFocus(
                                collapsed ? "shade_collapsed" : "shade_cleanup_finished");
                    });
        }, error -> {
            synchronized (GoogleCircleController.class) {
                if (gen != generation) {
                    hideLease.release(app);
                    return;
                }
            }
            restore(app, hideLease, gen, "capture_failed");
            DiagnosticLog.i(app, "G_CIRCLE", "capture failed="
                    + ScreenCaptureBackend.safeMessage(error));
            Toast.makeText(app, "圈画识别截图失败: "
                    + ScreenCaptureBackend.safeMessage(error), Toast.LENGTH_LONG).show();
        });
    }

    private static void restore(Context app, ScreenshotHideCoordinator.Lease lease,
                                long gen, String reason) {
        lease.release(app);
        synchronized (GoogleCircleController.class) {
            if (pendingHideLease == lease) pendingHideLease = null;
            if (gen != generation) return;
        }
        DiagnosticLog.i(app, "G_CIRCLE", "finish gen=" + gen + " reason=" + reason);
    }

    private static void cancelPendingLocked(Context app, String reason) {
        Future<?> future = contentFuture;
        contentFuture = null;
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            DiagnosticLog.i(app, "G_CIRCLE", "cancel semantic snapshot reason=" + reason
                    + " success=" + cancelled);
        }
        ScreenshotHideCoordinator.Lease lease = pendingHideLease;
        pendingHideLease = null;
        if (lease != null) lease.release(app);
    }

    private GoogleCircleController() {}
}
