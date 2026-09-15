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
        CropMath.Bounds bounds = CropMath.screenRectRound(
                screenBounds.left, screenBounds.top, screenBounds.right, screenBounds.bottom,
                display.left, display.top, display.width(), display.height(),
                raw.getWidth(), raw.getHeight());
        Bitmap crop = Bitmap.createBitmap(raw, bounds.left, bounds.top,
                bounds.width(), bounds.height());
        // A full-bounds Bitmap.createBitmap call may return the original object. Callers treat a
        // bounds crop as an independently owned result, so never leak the raw capture identity.
        if (crop == raw) {
            Bitmap copy = raw.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("unable to copy full screenshot crop");
            return copy;
        }
        return crop;
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
