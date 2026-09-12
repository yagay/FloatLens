package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.WindowManager;

import java.util.function.Consumer;

/**
 * Circle Select frame geometry.
 *
 * Keep the frozen frame in the exact full-display coordinate space. Do not crop status/navigation
 * bars before showing the workspace: the accessibility overlay also fills the full display, so the
 * captured pixels, OCR bounds, touch coordinates and crop coordinates stay 1:1 with the screen.
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
            DiagnosticLog.i(app, "CIRCLE_SELECT", "full frame display=" + display.toShortString()
                    + " bitmap=" + raw.getWidth() + "x" + raw.getHeight()
                    + " scaleX=" + (raw.getWidth() / (float) Math.max(1, display.width()))
                    + " scaleY=" + (raw.getHeight() / (float) Math.max(1, display.height())));
            ok.accept(raw);
        }, fail);
    }

    /** Circle Select now owns the complete display, including status and navigation bar areas. */
    static Rect contentBounds(Context c) {
        return displayBounds(c);
    }

    static Rect displayBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        return new Rect(wm.getCurrentWindowMetrics().getBounds());
    }

    private CircleSelectFrame() {}
}
