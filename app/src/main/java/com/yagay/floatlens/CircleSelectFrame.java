package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.WindowManager;

import java.util.function.Consumer;

/**
 * Circle Select frame geometry.
 *
 * Status-bar inclusion follows the shared screenshot setting. The live navigation bar is always
 * kept outside the interactive Circle Select workspace so Back / Home / Recents remain directly
 * clickable even when normal screenshots are configured to include navigation-bar pixels.
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
            Rect workspace = contentBounds(app);
            FloatSettings fs = new FloatSettings(app);
            boolean keepNavigation = CaptureSystemBarsPolicy.keepNavigationBar(app);
            Rect configuredCapture = CaptureSystemBarsPolicy.captureBounds(
                    app, fs.keepStatusBarInScreenshot(), keepNavigation);
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
                        + " keepStatusBar=" + fs.keepStatusBarInScreenshot()
                        + " keepNavigationBar=" + keepNavigation
                        + " liveNavigationAlwaysInteractive=true");
                ok.accept(frame);
            } catch (Throwable error) {
                if (!raw.isRecycled()) raw.recycle();
                fail.accept(error);
            }
        }, fail);
    }

    /**
     * Circle Select always excludes only the live navigation bar from its interactive window.
     * The status-bar choice still follows the user's screenshot-range setting.
     */
    static Rect contentBounds(Context c) {
        FloatSettings fs = new FloatSettings(c);
        return CaptureSystemBarsPolicy.captureBounds(
                c,
                fs.keepStatusBarInScreenshot(),
                false);
    }

    static Rect displayBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        return new Rect(wm.getCurrentWindowMetrics().getBounds());
    }

    private CircleSelectFrame() {}
}
