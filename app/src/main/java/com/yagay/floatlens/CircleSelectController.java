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

        FloatService service = FloatService.get();
        if (service != null) {
            service.onCircleCaptureStarted();
            service.setScreenshotHidden(true);
        }

        DiagnosticLog.i(app, "CIRCLE_SELECT", "capture begin gen=" + gen);
        MAIN.postDelayed(() -> ScreenshotController.captureRawFrame(app, bitmap -> {
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
            boolean shown = CircleSelectOverlay.show(app, bitmap, () -> restore(service, "closed"));
            if (!shown) {
                if (!bitmap.isRecycled()) bitmap.recycle();
                restore(service, "overlay_failed");
                Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
            }
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
