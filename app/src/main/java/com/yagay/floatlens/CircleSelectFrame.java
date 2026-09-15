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
            Rect workspace = contentBounds(app);
            FloatSettings settings = new FloatSettings(app);
            boolean keepNavigation = settings.keepNavigationBarInScreenshot();
            Rect configuredCapture = CaptureSystemBarsPolicy.captureBounds(
                    app, settings.keepStatusBarInScreenshot(), keepNavigation);
            try {
                Bitmap frame;
                if (workspace.equals(display)) {
                    frame = raw;
                } else {
                    frame = ScreenshotGeometry.cropScreenBounds(app, raw, workspace);
                    if (frame != raw && !raw.isRecycled()) raw.recycle();
                }
                DiagnosticLog.i(app, "CIRCLE_SELECT", "interactive frame display="
                        + display.toShortString()
                        + " configuredCapture=" + configuredCapture.toShortString()
                        + " workspace=" + workspace.toShortString()
                        + " bitmap=" + frame.getWidth() + "x" + frame.getHeight()
                        + " keepStatusBar=" + settings.keepStatusBarInScreenshot()
                        + " keepNavigationBar=" + keepNavigation
                        + " liveNavigationAlwaysInteractive=true");
                ok.accept(frame);
            } catch (Throwable error) {
                if (!raw.isRecycled()) raw.recycle();
                fail.accept(error);
            }
        }, fail);
    }

    /** Circle always leaves live navigation controls outside its interactive window. */
    static Rect contentBounds(Context c) {
        FloatSettings settings = new FloatSettings(c);
        return CaptureSystemBarsPolicy.captureBounds(
                c, settings.keepStatusBarInScreenshot(), false);
    }

    /** Compatibility alias; physical display ownership lives in ScreenGeometry. */
    static Rect displayBounds(Context c) { return ScreenGeometry.displayBounds(c); }

    private CircleSelectFrame() {}
}
