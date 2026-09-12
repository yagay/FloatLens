package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Display-space ↔ screenshot-space mapping shared by screenshot operations. */
final class ScreenshotGeometry {
    static Bitmap cropScreenBounds(Context c, Bitmap raw, Rect screenBounds) {
        if (raw == null || raw.isRecycled() || screenBounds == null || screenBounds.isEmpty()) {
            throw new IllegalArgumentException("invalid crop");
        }
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Rect display = wm.getCurrentWindowMetrics().getBounds();
        float sx = raw.getWidth() / (float) Math.max(1, display.width());
        float sy = raw.getHeight() / (float) Math.max(1, display.height());
        int left = clamp(Math.round((screenBounds.left - display.left) * sx), 0, raw.getWidth() - 1);
        int top = clamp(Math.round((screenBounds.top - display.top) * sy), 0, raw.getHeight() - 1);
        int right = clamp(Math.round((screenBounds.right - display.left) * sx), left + 1, raw.getWidth());
        int bottom = clamp(Math.round((screenBounds.bottom - display.top) * sy), top + 1, raw.getHeight());
        return Bitmap.createBitmap(raw, left, top, right - left, bottom - top);
    }

    static Bitmap maybeCropStatusBar(Context c, Bitmap raw, boolean keepStatusBar) {
        if (raw == null || raw.isRecycled() || keepStatusBar) return raw;
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            var metrics = wm.getCurrentWindowMetrics();
            var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars());
            int screenH = metrics.getBounds().height();
            int topPx = insets.top;
            if (topPx <= 0 || screenH <= 0) return raw;
            float sy = raw.getHeight() / (float) screenH;
            int cropTop = clamp(Math.round(topPx * sy), 0, raw.getHeight() - 1);
            return Bitmap.createBitmap(raw, 0, cropTop, raw.getWidth(), raw.getHeight() - cropTop);
        } catch (Throwable ignored) {
            return raw;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private ScreenshotGeometry() {}
}
