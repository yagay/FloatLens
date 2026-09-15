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
    private static final long SECURE_CAPTURE_TTL_MS = 1500L;
    private static final long SECURE_CAPTURE_ARM_SETTLE_MS = 80L;
    private static final long RESTORE_DELAY_MS = 80L;

    static void capture(Context c, FloatSettings settings,
                        Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        boolean hideIcon = !settings.keepInScreenshot() && FloatService.get() != null;
        ScreenshotHideCoordinator.Lease hideLease = hideIcon
                ? ScreenshotHideCoordinator.acquire(app, "screenshot_session") : null;
        boolean secureEnhancement = settings.effectiveLsposedSecureScreenshot();
        DiagnosticLog.i(app, "SCREENSHOT_SESSION", "begin hideIcon=" + hideIcon
                + " accessibility=" + settings.accessibilityScreenshot()
                + " root=" + settings.rootScreenshot()
                + " lsposedSecure=" + secureEnhancement);

        MAIN.postDelayed(() -> {
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "capture after settleMs="
                    + (hideIcon ? HIDE_SETTLE_MS : 0L));
            Runnable runCapture = () -> captureBackend(app, settings, secureEnhancement,
                    hideLease, ok, fail);
            if (!secureEnhancement) {
                runCapture.run();
                return;
            }

            LsposedStatusManager.beginSecureCaptureWindow(SECURE_CAPTURE_TTL_MS, armed -> {
                DiagnosticLog.i(app, "LSPOSED_SECURE_CAPTURE",
                        "request armed=" + armed + " ttlMs=" + SECURE_CAPTURE_TTL_MS);
                MAIN.postDelayed(runCapture, armed ? SECURE_CAPTURE_ARM_SETTLE_MS : 0L);
            });
        }, hideIcon ? HIDE_SETTLE_MS : 0L);
    }

    private static void captureBackend(Context app, FloatSettings settings, boolean secureEnhancement,
                                       ScreenshotHideCoordinator.Lease hideLease,
                                       Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        ScreenCaptureBackend.capture(app, settings, raw -> {
            if (secureEnhancement) LsposedStatusManager.endSecureCaptureWindow();
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend success bitmap=" + size(raw));
            restore(app, hideLease);
            ok.accept(raw);
        }, error -> {
            if (secureEnhancement) LsposedStatusManager.endSecureCaptureWindow();
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "backend failed error="
                    + ScreenCaptureBackend.safeMessage(error));
            restore(app, hideLease);
            fail.accept(error);
        });
    }

    private static void restore(Context app, ScreenshotHideCoordinator.Lease lease) {
        if (lease == null) return;
        MAIN.postDelayed(() -> {
            lease.release(app);
            DiagnosticLog.i(app, "SCREENSHOT_SESSION", "hide lease released delayMs=" + RESTORE_DELAY_MS);
        }, RESTORE_DELAY_MS);
    }

    private static String size(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }

    private ScreenshotCaptureSession() {}
}
