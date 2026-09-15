package com.yagay.floatlens;

import android.content.Context;
import android.widget.Toast;

/** Entry point for the new Google-style circle workflow. Legacy CircleSelect remains isolated. */
final class GoogleCircleController {
    private static long generation;
    private static ScreenshotHideCoordinator.Lease pendingHideLease;

    static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;

        GoogleCircleOverlay.dismissActive("restart");
        if (pendingHideLease != null) {
            pendingHideLease.release(app);
            pendingHideLease = null;
        }

        FlSystemPanelController.CaptureState shadeState =
                FlSystemPanelController.beginCapture(app, "google_circle");
        ScreenshotHideCoordinator.Lease hideLease =
                ScreenshotHideCoordinator.acquire(app, "google_circle_" + gen);
        pendingHideLease = hideLease;
        DiagnosticLog.i(app, "G_CIRCLE", "start gen=" + gen);

        GoogleCircleCapture.capture(app, frame -> {
            synchronized (GoogleCircleController.class) {
                if (gen != generation) {
                    frame.recycle();
                    hideLease.release(app);
                    return;
                }
            }
            boolean shown = GoogleCircleOverlay.show(app, frame,
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

    private GoogleCircleController() {}
}
