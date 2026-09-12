package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.function.Consumer;

/**
 * One screenshot lifecycle for all callers: optionally hide FloatLens, settle, capture, then restore.
 * Cropping/result routing intentionally stays outside this class.
 */
final class ScreenshotCaptureSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long HIDE_SETTLE_MS = 100L;
    private static final long RESTORE_DELAY_MS = 80L;

    static void capture(Context c, FloatSettings settings,
                        Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        FloatService service = FloatService.get();
        boolean hideIcon = !settings.keepInScreenshot() && service != null;
        DiagnosticLog.i(app, "SCREENSHOT_SESSION", "begin hideIcon=" + hideIcon
                + " accessibility=" + settings.accessibilityScreenshot()
                + " root=" + settings.rootScreenshot());
        if (hideIcon) service.setScreenshotHidden(true);

        MAIN.postDelayed(() -> {
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "capture after settleMs="
                    + (hideIcon ? HIDE_SETTLE_MS : 0L));
            ScreenCaptureBackend.capture(app, settings, raw -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend success bitmap=" + size(raw));
                restore(app, service, hideIcon);
                ok.accept(raw);
            }, error -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend failed error="
                        + ScreenCaptureBackend.safeMessage(error));
                restore(app, service, hideIcon);
                fail.accept(error);
            });
        }, hideIcon ? HIDE_SETTLE_MS : 0L);
    }

    private static void restore(Context app, FloatService service, boolean hidden) {
        if (!hidden || service == null) return;
        MAIN.postDelayed(() -> {
            service.setScreenshotHidden(false);
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "icon restored delayMs=" + RESTORE_DELAY_MS);
        }, RESTORE_DELAY_MS);
    }

    private static String size(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }

    private ScreenshotCaptureSession() {}
}
