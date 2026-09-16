package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.function.Consumer;

/** Circle Select frame geometry on top of the shared display/system-bar policy. */
final class CircleSelectFrame {
    static void capture(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        ScreenshotController.captureRawFrame(app, raw -> {
            if (raw == null || raw.isRecycled() || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
                fail.accept(new IllegalArgumentException("invalid Circle Select frame"));
                return;
            }

            Rect display = ScreenGeometry.displayBounds(app);
            Rect content = contentBounds(app);
            FloatSettings settings = new FloatSettings(app);
            boolean keepNavigation = settings.keepNavigationBarInScreenshot();
            try {
                Bitmap frame;
                if (content.equals(display)) {
                    frame = raw;
                } else {
                    frame = ScreenshotGeometry.cropScreenBounds(app, raw, content);
                    if (frame != raw && !raw.isRecycled()) raw.recycle();
                }
                DiagnosticLog.i(app, "CIRCLE_SELECT", "configured frame display="
                        + display.toShortString()
                        + " content=" + content.toShortString()
                        + " bitmap=" + frame.getWidth() + "x" + frame.getHeight()
                        + " keepStatusBar=" + settings.keepStatusBarInScreenshot()
                        + " keepNavigationBar=" + keepNavigation);
                ok.accept(frame);
            } catch (Throwable error) {
                if (!raw.isRecycled()) raw.recycle();
                fail.accept(error);
            }
        }, fail);
    }

    static Rect contentBounds(Context c) {
        FloatSettings settings = new FloatSettings(c);
        return CaptureSystemBarsPolicy.captureBounds(
                c,
                settings.keepStatusBarInScreenshot(),
                settings.keepNavigationBarInScreenshot());
    }

    /** Compatibility alias; physical display ownership lives in ScreenGeometry. */
    static Rect displayBounds(Context c) { return ScreenGeometry.displayBounds(c); }

    private CircleSelectFrame() {}
}
