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
        if (hideIcon) service.setScreenshotHidden(true);

        MAIN.postDelayed(() -> ScreenCaptureBackend.capture(app, settings, raw -> {
            restore(service, hideIcon);
            ok.accept(raw);
        }, error -> {
            restore(service, hideIcon);
            fail.accept(error);
        }), hideIcon ? HIDE_SETTLE_MS : 0L);
    }

    private static void restore(FloatService service, boolean hidden) {
        if (!hidden || service == null) return;
        MAIN.postDelayed(() -> service.setScreenshotHidden(false), RESTORE_DELAY_MS);
    }

    private ScreenshotCaptureSession() {}
}
