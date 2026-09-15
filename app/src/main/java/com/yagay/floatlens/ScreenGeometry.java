package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;
import android.view.WindowManager;

/** Single owner for physical display geometry used outside Activity content-inset handling. */
final class ScreenGeometry {
    static Rect displayBounds(Context context) {
        if (context == null) return new Rect();
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Rect bounds = wm == null ? null : wm.getCurrentWindowMetrics().getBounds();
            return bounds == null ? new Rect() : new Rect(bounds);
        } catch (Throwable ignored) {
            return new Rect();
        }
    }

    static float density(Context context) {
        if (context == null) return 1f;
        try {
            return Math.max(0.1f, context.getResources().getDisplayMetrics().density);
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    static int dp(Context context, float value) {
        return Math.round(value * density(context));
    }

    private ScreenGeometry() {}
}
