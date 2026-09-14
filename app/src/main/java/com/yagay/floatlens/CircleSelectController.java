package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Entry point for the Google-like local Circle Select workspace. */
public final class CircleSelectController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long generation;

    public static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;
        CircleSelectOverlay.dismissActive("restart");

        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "circle_select");

        FloatService service = FloatService.get();
        if (service != null) service.onCircleCaptureStarted();
        ScreenshotVisibilityLease.Lease visibilityLease =
                ScreenshotVisibilityLease.acquire(app, "circle_select_" + gen);

        DiagnosticLog.i(app, "CIRCLE_SELECT", "capture begin gen=" + gen
                + " shadeExpanded=" + shadeState.expandedAtCapture());
        MAIN.postDelayed(() -> {
            if (!isCurrent(gen)) {
                ScreenshotVisibilityLease.release(app, visibilityLease, "stale_before_capture");
                return;
            }
            CircleSelectFrame.capture(app, bitmap -> {
                if (!isCurrent(gen)) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    ScreenshotVisibilityLease.release(app, visibilityLease, "stale_success");
                    return;
                }
                if (bitmap == null || bitmap.isRecycled()) {
                    restore(app, service, visibilityLease, gen, "invalid_capture");
                    return;
                }

                boolean shown = CircleSelectOverlay.show(app, bitmap,
                        () -> restore(app, service, visibilityLease, gen, "closed"));
                DiagnosticLog.i(app, "CIRCLE_SELECT", "accessibility workspace shown=" + shown
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                if (!shown) {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    restore(app, service, visibilityLease, gen, "overlay_failed");
                    Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
                    return;
                }

                OverlayShadeCoordinator.cleanup(app, shadeState.expandedAtCapture(), "circle_select",
                        collapsed -> {
                            if (!isCurrent(gen)) return;
                            DiagnosticLog.i(app, "CIRCLE_SELECT", "background shade cleanup collapsed="
                                    + collapsed + " gen=" + gen);
                            CircleSelectOverlay.promoteActiveFocus(
                                    collapsed ? "shade_collapsed" : "shade_cleanup_exhausted");
                        });
            }, error -> {
                if (!isCurrent(gen)) {
                    ScreenshotVisibilityLease.release(app, visibilityLease, "stale_failure");
                    DiagnosticLog.i(app, "CIRCLE_SELECT", "drop stale capture failure gen=" + gen
                            + " current=" + currentGeneration());
                    return;
                }
                restore(app, service, visibilityLease, gen, "capture_failed");
                Toast.makeText(app, "圈画识别截图失败: " + safe(error), Toast.LENGTH_LONG).show();
                DiagnosticLog.i(app, "CIRCLE_SELECT", "capture failed=" + safe(error));
            });
        }, 90L);
    }

    private static void restore(Context app, FloatService service,
                                ScreenshotVisibilityLease.Lease visibilityLease,
                                long gen, String reason) {
        ScreenshotVisibilityLease.release(app, visibilityLease, reason);
        if (service != null && isCurrent(gen)) {
            service.onCircleFinished("circle_select_" + reason);
        }
    }

    private static synchronized boolean isCurrent(long gen) {
        return gen == generation;
    }

    private static synchronized long currentGeneration() {
        return generation;
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private CircleSelectController() {}
}
