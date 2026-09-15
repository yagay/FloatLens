package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.function.Consumer;

/**
 * Circle Select frame geometry.
 *
 * Keep the interactive workspace inside the app content area instead of covering live system bars.
 * The frozen screenshot is cropped to the same bounds before it reaches the overlay, so screenshot
 * pixels, OCR bounds, touch coordinates and result crops keep the same 1:1 workspace geometry.
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
                Bitmap frame = ScreenshotGeometry.cropScreenBounds(app, raw, content);
                if (frame != raw && !raw.isRecycled()) raw.recycle();
                DiagnosticLog.i(app, "CIRCLE_SELECT", "content frame display=" + display.toShortString()
                        + " content=" + content.toShortString()
                        + " bitmap=" + frame.getWidth() + "x" + frame.getHeight()
                        + " systemBarsExcluded=" + !content.equals(display));
                ok.accept(frame);
            } catch (Throwable error) {
                if (!raw.isRecycled()) raw.recycle();
                fail.accept(error);
            }
        }, fail);
    }

    /**
     * Bounds available to Circle Select while keeping visible status/navigation bars live.
     * In three-button navigation this leaves Back/Home/Recents outside the overlay so SystemUI can
     * receive the taps; CircleSelectOverlay/LensAccessibilityService then close the active session.
     */
    static Rect contentBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        var metrics = wm.getCurrentWindowMetrics();
        Rect display = new Rect(metrics.getBounds());
        try {
            Insets bars = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars());
            Rect content = new Rect(
                    display.left + Math.max(0, bars.left),
                    display.top + Math.max(0, bars.top),
                    display.right - Math.max(0, bars.right),
                    display.bottom - Math.max(0, bars.bottom));
            if (!content.isEmpty()) return content;
        } catch (Throwable ignored) {
            // Fall back to the full display rather than failing Circle Select on unusual OEM metrics.
        }
        return display;
    }

    static Rect displayBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        return new Rect(wm.getCurrentWindowMetrics().getBounds());
    }

    private CircleSelectFrame() {}
}
