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
        boolean hideIcon = !settings.keepInScreenshot() && FloatService.get() != null;
        CaptureTransaction visuals = CaptureTransaction.visual(app, "screenshot_session");
        if (hideIcon) visuals.hideFloatingIcon("screenshot_session");
        visuals.hideCircleBorder("screenshot_session");
        boolean hideBorder = visuals.circleBorderHidden();
        long settleMs = (hideIcon || hideBorder) ? HIDE_SETTLE_MS : 0L;

        DiagnosticLog.i(app, "SCREENSHOT_SESSION", "begin hideIcon=" + hideIcon
                + " hideCircleBorder=" + hideBorder
                + " accessibility=" + settings.accessibilityScreenshot()
                + " root=" + settings.rootScreenshot());

        MAIN.postDelayed(() -> {
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "capture after settleMs=" + settleMs);
            ScreenCaptureBackend.capture(app, settings, raw -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend success bitmap=" + size(raw));
                restore(app, visuals);
                ok.accept(raw);
            }, error -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend failed error="
                        + ScreenCaptureBackend.safeMessage(error));
                restore(app, visuals);
                fail.accept(error);
            });
        }, settleMs);
    }

    private static void restore(Context app, CaptureTransaction visuals) {
        MAIN.postDelayed(() -> {
            visuals.close();
            DiagnosticLog.i(app, "SCREENSHOT_SESSION",
                    "visual hide transaction released delayMs=" + RESTORE_DELAY_MS);
        }, RESTORE_DELAY_MS);
    }

    private static String size(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }

    private ScreenshotCaptureSession() {}
}
