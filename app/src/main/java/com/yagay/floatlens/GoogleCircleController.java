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
                + " phase=capture_then_ocr_preindex"
                + " textRecognition=ocr_only_full_then_local3"
                + " classifier=full_ocr_then_local_three_variant"
                + " viewText=false semanticLabels=false"
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

            // Build one frozen full-screen OCR index immediately. Ordinary text is selected from
            // that SCREEN-space document. Small image text/logos and genuine misses use only the
            // user's tight local crop; Accessibility/View text never participates in Circle.
            GoogleCircleTextResolver.preload(app, frame);

            boolean shown = GoogleCircleInlineOverlay.show(app, frame, () -> {
                GoogleCircleTextResolver.release(app, frame, "workspace_closed");
                CircleActiveBorderOverlay.hide(app, "workspace_closed");
                restore(app, hideLease, gen, "closed");
            });
            if (!shown) {
                GoogleCircleTextResolver.release(app, frame, "overlay_failed");
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
