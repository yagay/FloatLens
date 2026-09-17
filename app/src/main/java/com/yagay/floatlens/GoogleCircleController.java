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
        int ocrEngineMode = new FloatSettings(app).ocrEngineMode();
        DiagnosticLog.i(app, "G_CIRCLE", "start gen=" + gen
                + " phase=capture_then_fullscreen_ocr"
                + " layoutDetection=disabled"
                + " paragraphStitching=disabled"
                + " textRecognition=settings_selected_fullscreen_once"
                + " ocrEngineMode=" + ocrEngineMode
                + " regionCache=full_frozen_frame"
                + " backgroundOcr=false tileOcr=false fullFrameOcr=true"
                + " preindexBlocking=false localFallback=last_resort_only"
                + " viewText=false semanticLabels=false contentHints=false"
                + " gestureGeometry=exact_path"
                + " tapSelection=precise_char_or_latin_word"
                + " circleMode=editable_screenshot autoExpand=false");

        GoogleCircleCapture.capture(app, frame -> {
            synchronized (GoogleCircleController.class) {
                if (gen != generation) {
                    frame.recycle();
                    hideLease.release(app);
                    return;
                }
            }

            // Initialize per-frame full-screen OCR state. Recognition itself starts on the first
            // text gesture, follows the OCR engine selected in Settings, and is then reused for
            // every later tap/highlight/scribble in this frozen frame.
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
