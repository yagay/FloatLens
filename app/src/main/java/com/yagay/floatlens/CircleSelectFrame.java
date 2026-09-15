package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.WindowManager;

import java.util.function.Consumer;

/**
 * Circle Select frame geometry.
 *
 * Circle Select uses the same status/navigation-bar capture policy as normal screenshots. The
 * frozen screenshot is cropped to the exact same bounds as the overlay so screenshot pixels, OCR
 * bounds, touch coordinates and result crops stay in one coordinate space.
 */
final class CircleSelectFrame {
    static void capture(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        ScreenshotController.captureRawFrame(app, raw -> {
            if (raw == null || raw.isRecycled() || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
                fail.accept(new IllegalArgumentException("invalid Circle Select frame"));
                return;
            }

            Rect display = displayBounds(app);
            Rect content = contentBounds(app);
            try {
                Bitmap frame;
                if (content.equals(display)) {
                    frame = raw;
                } else {
                    frame = ScreenshotGeometry.cropScreenBounds(app, raw, content);
                    if (frame != raw && !raw.isRecycled()) raw.recycle();
                }
                FloatSettings fs = new FloatSettings(app);
                boolean keepNavigation = CaptureSystemBarsPolicy.keepNavigationBar(app);
                DiagnosticLog.i(app, "CIRCLE_SELECT", "configured frame display="
                        + display.toShortString()
                        + " content=" + content.toShortString()
                        + " bitmap=" + frame.getWidth() + "x" + frame.getHeight()
                        + " keepStatusBar=" + fs.keepStatusBarInScreenshot()
                        + " keepNavigationBar=" + keepNavigation);
                ok.accept(frame);
            } catch (Throwable error) {
                if (!raw.isRecycled()) raw.recycle();
                fail.accept(error);
            }
        }, fail);
    }

    static Rect contentBounds(Context c) {
        FloatSettings fs = new FloatSettings(c);
        return CaptureSystemBarsPolicy.captureBounds(
                c,
                fs.keepStatusBarInScreenshot(),
                CaptureSystemBarsPolicy.keepNavigationBar(c));
    }

    static Rect displayBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        return new Rect(wm.getCurrentWindowMetrics().getBounds());
    }

    private CircleSelectFrame() {}
}
