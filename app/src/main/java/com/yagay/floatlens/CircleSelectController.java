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
        if (service != null) {
            service.onCircleCaptureStarted();
            service.setScreenshotHidden(true);
        }

        DiagnosticLog.i(app, "CIRCLE_SELECT", "capture begin gen=" + gen
                + " shadeExpanded=" + shadeState.expandedAtCapture());
        MAIN.postDelayed(() -> CircleSelectFrame.capture(app, bitmap -> {
            synchronized (CircleSelectController.class) {
                if (gen != generation) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
            }
            if (bitmap == null || bitmap.isRecycled()) {
                restore(service, "invalid_capture");
                return;
            }

            boolean shown = CircleSelectOverlay.show(app, bitmap,
                    () -> restore(service, "closed"));
            DiagnosticLog.i(app, "CIRCLE_SELECT", "accessibility workspace shown=" + shown
                    + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            if (!shown) {
                if (!bitmap.isRecycled()) bitmap.recycle();
                restore(service, "overlay_failed");
                Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
                return;
            }

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
            restore(service, "capture_failed");
            Toast.makeText(app, "圈画识别截图失败: " + safe(error), Toast.LENGTH_LONG).show();
            DiagnosticLog.i(app, "CIRCLE_SELECT", "capture failed=" + safe(error));
        }), 90L);
    }

    private static void restore(FloatService service, String reason) {
        if (service != null) {
            service.setScreenshotHidden(false);
            service.onCircleFinished("circle_select_" + reason);
        }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private CircleSelectController() {}
}
