package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.content.Context;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.function.Consumer;

/** Circle Select frame geometry: only the visible app-content area, excluding system bars. */
final class CircleSelectFrame {
    static void capture(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        ScreenshotController.captureRawFrame(app, raw -> {
            try {
                Bitmap cropped = cropToVisibleContent(app, raw);
                ok.accept(cropped);
            } catch (Throwable t) {
                try { if (raw != null && !raw.isRecycled()) raw.recycle(); } catch (Throwable ignored) {}
                fail.accept(t);
            }
        }, fail);
    }

    /** Visible display area after removing only currently visible status/navigation bars. */
    static Rect contentBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        var metrics = wm.getCurrentWindowMetrics();
        Rect display = new Rect(metrics.getBounds());
        Insets bars = metrics.getWindowInsets().getInsets(
                WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        Rect content = new Rect(
                display.left + Math.max(0, bars.left),
                display.top + Math.max(0, bars.top),
                display.right - Math.max(0, bars.right),
                display.bottom - Math.max(0, bars.bottom));
        if (content.width() <= 0 || content.height() <= 0) return display;
        return content;
    }

    static Rect displayBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        return new Rect(wm.getCurrentWindowMetrics().getBounds());
    }

    private static Bitmap cropToVisibleContent(Context c, Bitmap raw) {
        if (raw == null || raw.isRecycled() || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
            throw new IllegalArgumentException("invalid Circle Select frame");
        }
        Rect display = displayBounds(c);
        Rect content = contentBounds(c);
        if (content.equals(display)) return raw;

        float sx = raw.getWidth() / (float) Math.max(1, display.width());
        float sy = raw.getHeight() / (float) Math.max(1, display.height());
        int left = clamp(Math.round((content.left - display.left) * sx), 0, raw.getWidth() - 1);
        int top = clamp(Math.round((content.top - display.top) * sy), 0, raw.getHeight() - 1);
        int right = clamp(Math.round((content.right - display.left) * sx), left + 1, raw.getWidth());
        int bottom = clamp(Math.round((content.bottom - display.top) * sy), top + 1, raw.getHeight());

        if (left == 0 && top == 0 && right == raw.getWidth() && bottom == raw.getHeight()) return raw;
        Bitmap cropped = Bitmap.createBitmap(raw, left, top, right - left, bottom - top);
        if (cropped != raw) {
            try { raw.recycle(); } catch (Throwable ignored) {}
        }
        DiagnosticLog.i(c, "CIRCLE_SELECT", "content crop display=" + display.toShortString()
                + " content=" + content.toShortString() + " bitmap="
                + cropped.getWidth() + "x" + cropped.getHeight());
        return cropped;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private CircleSelectFrame() {}
}
