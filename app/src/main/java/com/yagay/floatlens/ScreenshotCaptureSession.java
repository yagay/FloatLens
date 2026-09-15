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
        ScreenshotHideCoordinator.Lease hideLease = hideIcon
                ? ScreenshotHideCoordinator.acquire(app, "screenshot_session") : null;
        CircleActiveBorderOverlay.CaptureLease borderLease =
                CircleActiveBorderOverlay.acquireCaptureHidden(app, "screenshot_session");
        boolean hideBorder = borderLease.requiresSettle();
        long settleMs = (hideIcon || hideBorder) ? HIDE_SETTLE_MS : 0L;

        DiagnosticLog.i(app, "SCREENSHOT_SESSION", "begin hideIcon=" + hideIcon
                + " hideCircleBorder=" + hideBorder
                + " accessibility=" + settings.accessibilityScreenshot()
                + " root=" + settings.rootScreenshot());

        MAIN.postDelayed(() -> {
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "capture after settleMs=" + settleMs);
            ScreenCaptureBackend.capture(app, settings, raw -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend success bitmap=" + size(raw));
                restore(app, hideLease, borderLease);
                ok.accept(raw);
            }, error -> {
                DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend failed error="
                        + ScreenCaptureBackend.safeMessage(error));
                restore(app, hideLease, borderLease);
                fail.accept(error);
            });
        }, settleMs);
    }

    private static void restore(Context app,
                                ScreenshotHideCoordinator.Lease iconLease,
                                CircleActiveBorderOverlay.CaptureLease borderLease) {
        MAIN.postDelayed(() -> {
            if (iconLease != null) iconLease.release(app);
            if (borderLease != null) borderLease.release(app);
            DiagnosticLog.i(app, "SCREENSHOT_SESSION",
                    "visual hide leases released delayMs=" + RESTORE_DELAY_MS);
        }, RESTORE_DELAY_MS);
    }

    private static String size(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }

    private ScreenshotCaptureSession() {}
}
