package com.yagay.floatlens;

import android.content.Context;
import android.widget.Toast;

/** Entry point for the Google-style exact content-selection workflow. */
final class GoogleCircleController {
    private static long generation;
    private static ScreenshotHideCoordinator.Lease pendingHideLease;

    static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;

        GoogleCircleInlineOverlay.dismissActive("restart");
        CircleActiveBorderOverlay.hide(app, "restart");
        cancelPendingLocked(app);

        FlSystemPanelController.CaptureState shadeState =
                FlSystemPanelController.beginCapture(app, "google_circle");
        ScreenshotHideCoordinator.Lease hideLease =
                ScreenshotHideCoordinator.acquire(app, "google_circle_" + gen);
        pendingHideLease = hideLease;
        DiagnosticLog.i(app, "G_CIRCLE", "start gen=" + gen
                + " phase=capture_then_text_preindex"
                + " textRecognition=independent_full_plus_tiles"
                + " classifier=view_then_cached_consensus_then_local_ocr"
                + " gestureGeometry=exact_path"
                + " circleMode=editable_screenshot autoExpand=false");

        GoogleCircleCapture.capture(app, frame -> {
            synchronized (GoogleCircleController.class) {
                if (gen != generation) {
                    frame.recycle();
                    hideLease.release(app);
                    return;
                }
            }

            // Build frozen View text plus independent full/tile OCR indexes immediately. Most later
            // gestures only query these SCREEN-space caches; a tight local OCR request is permitted
            // only when both View and every cached OCR pass genuinely miss the user's target.
            GoogleCircleTextResolver.preload(app, frame);

            boolean shown = GoogleCircleInlineOverlay.show(app, frame, () -> {
                CircleActiveBorderOverlay.hide(app, "workspace_closed");
                restore(app, hideLease, gen, "closed");
            });
            if (!shown) {
                frame.recycle();
                CircleActiveBorderOverlay.hide(app, "overlay_failed");
                restore(app, hideLease, gen, "overlay_failed");
                Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
                return;
            }

            // The edge indicator is added after the frozen frame and workspace are ready. Therefore
            // it stays above the workspace but can never be captured into the frozen source bitmap.
            CircleActiveBorderOverlay.show(app);

            FlSystemPanelController.onOverlayReady(app, shadeState, "google_circle",
                    collapsed -> {
                        synchronized (GoogleCircleController.class) {
                            if (gen != generation) return;
                        }
                        DiagnosticLog.i(app, "G_CIRCLE", "shade cleanup collapsed="
                                + collapsed + " gen=" + gen);
                        GoogleCircleInlineOverlay.promoteActiveFocus(
                                collapsed ? "shade_collapsed" : "shade_cleanup_finished");
                    });
        }, error -> {
            synchronized (GoogleCircleController.class) {
                if (gen != generation) {
                    hideLease.release(app);
                    return;
                }
            }
            CircleActiveBorderOverlay.hide(app, "capture_failed");
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

    private static void cancelPendingLocked(Context app) {
        ScreenshotHideCoordinator.Lease lease = pendingHideLease;
        pendingHideLease = null;
        if (lease != null) lease.release(app);
    }

    private GoogleCircleController() {}
}
