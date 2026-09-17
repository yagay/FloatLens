package com.yagay.floatlens;

import android.content.Context;
import android.widget.Toast;

/** Entry point for the FloatLens exact content-selection workflow. */
final class FLCircleController {
    private static long generation;
    private static ScreenshotHideCoordinator.Lease pendingHideLease;

    static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;

        FLCircleInlineOverlay.dismissActive("restart");
        CircleActiveBorderOverlay.hide(app, "restart");
        cancelPendingLocked(app);

        FlSystemPanelController.CaptureState shadeState =
                FlSystemPanelController.beginCapture(app, "fl_circle");
        ScreenshotHideCoordinator.Lease hideLease =
                ScreenshotHideCoordinator.acquire(app, "fl_circle_" + gen);
        pendingHideLease = hideLease;
        FloatSettings fs = new FloatSettings(app);
        DiagnosticLog.i(app, "FL_CIRCLE", "start gen=" + gen
                + " phase=capture_then_fullscreen_ocr"
                + " fullEngine=" + CircleStableOcr.fullModeLabel(app)
                + " fullMode=" + fs.circleFullOcrEngine()
                + " correctionEngine=" + CircleStableOcr.correctionModeLabel(app)
                + " correctionMode=" + fs.circleCorrectionEngine()
                + " textRecognition=full_once_plus_optional_per_gesture_correction"
                + " layoutDetection=disabled paragraphStitching=disabled"
                + " backgroundOcr=false tileOcr=false fullFrameOcr=true"
                + " viewText=false semanticLabels=false contentHints=false"
                + " gestureGeometry=exact_path"
                + " tapSelection=precise_char_or_latin_word"
                + " circleMode=editable_screenshot autoExpand=false");

        FLCircleCapture.capture(app, frame -> {
            synchronized (FLCircleController.class) {
                if (gen != generation) {
                    frame.recycle();
                    hideLease.release(app);
                    return;
                }
            }

            // Initialize per-frame OCR state. Full-screen recognition starts on the first text
            // gesture and is cached. Optional PP correction is then evaluated on every text gesture.
            FLCircleTextResolver.preload(app, frame);

            boolean shown = FLCircleInlineOverlay.show(app, frame, () -> {
                FLCircleTextResolver.release(app, frame, "workspace_closed");
                CircleActiveBorderOverlay.hide(app, "workspace_closed");
                restore(app, hideLease, gen, "closed");
            });
            if (!shown) {
                FLCircleTextResolver.release(app, frame, "overlay_failed");
                frame.recycle();
                CircleActiveBorderOverlay.hide(app, "overlay_failed");
                restore(app, hideLease, gen, "overlay_failed");
                Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
                return;
            }

            CircleActiveBorderOverlay.show(app);

            FlSystemPanelController.onOverlayReady(app, shadeState, "fl_circle",
                    collapsed -> {
                        synchronized (FLCircleController.class) {
                            if (gen != generation) return;
                        }
                        DiagnosticLog.i(app, "FL_CIRCLE", "shade cleanup collapsed="
                                + collapsed + " gen=" + gen);
                        FLCircleInlineOverlay.promoteActiveFocus(
                                collapsed ? "shade_collapsed" : "shade_cleanup_finished");
                    });
        }, error -> {
            synchronized (FLCircleController.class) {
                if (gen != generation) {
                    hideLease.release(app);
                    return;
                }
            }
            CircleActiveBorderOverlay.hide(app, "capture_failed");
            restore(app, hideLease, gen, "capture_failed");
            DiagnosticLog.i(app, "FL_CIRCLE", "capture failed="
                    + ScreenCaptureBackend.safeMessage(error));
            Toast.makeText(app, "圈画识别截图失败: "
                    + ScreenCaptureBackend.safeMessage(error), Toast.LENGTH_LONG).show();
        });
    }

    private static void restore(Context app, ScreenshotHideCoordinator.Lease lease,
                                long gen, String reason) {
        lease.release(app);
        synchronized (FLCircleController.class) {
            if (pendingHideLease == lease) pendingHideLease = null;
            if (gen != generation) return;
        }
        DiagnosticLog.i(app, "FL_CIRCLE", "finish gen=" + gen + " reason=" + reason);
    }

    private static void cancelPendingLocked(Context app) {
        ScreenshotHideCoordinator.Lease lease = pendingHideLease;
        pendingHideLease = null;
        if (lease != null) lease.release(app);
    }

    private FLCircleController() {}
}
