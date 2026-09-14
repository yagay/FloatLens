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

    static void capture(Context c, FloatSettings settings,
                        Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        boolean hideIcon = !settings.keepInScreenshot() && FloatService.get() != null;
        ScreenshotVisibilityLease.Lease visibilityLease = hideIcon
                ? ScreenshotVisibilityLease.acquire(app, "screenshot_session") : null;
        DiagnosticLog.i(app, "SCREENSHOT_SESSION", "begin hideIcon=" + hideIcon
                + " accessibility=" + settings.accessibilityScreenshot()
                + " root=" + settings.rootScreenshot());

        MAIN.postDelayed(() -> {
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "capture after settleMs="
                    + (hideIcon ? HIDE_SETTLE_MS : 0L));
            ScreenCaptureBackend.capture(app, settings, raw -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend success bitmap=" + size(raw));
                ScreenshotVisibilityLease.release(app, visibilityLease, "success");
                ok.accept(raw);
            }, error -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend failed error="
                        + ScreenCaptureBackend.safeMessage(error));
                ScreenshotVisibilityLease.release(app, visibilityLease, "failure");
                fail.accept(error);
            });
        }, hideIcon ? HIDE_SETTLE_MS : 0L);
    }

    private static String size(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }

    private ScreenshotCaptureSession() {}
}
