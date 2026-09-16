package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

/** Display-space ↔ screenshot-space mapping shared by screenshot operations. */
final class ScreenshotGeometry {
    static Bitmap cropScreenBounds(Context c, Bitmap raw, Rect screenBounds) {
        if (raw == null || raw.isRecycled() || screenBounds == null || screenBounds.isEmpty()) {
            throw new IllegalArgumentException("invalid crop");
        }
        Rect display = ScreenGeometry.displayBounds(c);
        if (display.isEmpty()) throw new IllegalStateException("display bounds unavailable");

        ScreenBitmapTransform transform = new ScreenBitmapTransform(
                display, raw.getWidth(), raw.getHeight());
        Rect bounds = transform.screenToBitmap(screenBounds);
        if (bounds.isEmpty()) throw new IllegalArgumentException("crop outside display");

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

    /**
     * Applies the shared screenshot/Circle Select system-bar policy.
     * The legacy method name is kept to avoid widening this change through unrelated callers.
     */
    static Bitmap maybeCropStatusBar(Context c, Bitmap raw, boolean keepStatusBar) {
        if (raw == null || raw.isRecycled()) return raw;
        boolean keepNavigationBar = CaptureSystemBarsPolicy.keepNavigationBar(c);
        Rect captureBounds = CaptureSystemBarsPolicy.captureBounds(
                c, keepStatusBar, keepNavigationBar);
        Rect display = ScreenGeometry.displayBounds(c);
        if (captureBounds.equals(display)) return raw;
        try {
            return cropScreenBounds(c, raw, captureBounds);
        } catch (Throwable ignored) {
            return raw;
        }
    }

    private ScreenshotGeometry() {}
}
