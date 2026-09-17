package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;
import android.view.WindowInsets;
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

    /** Physical screen area excluding status/navigation system bars. */
    static Rect usableBounds(Context context) {
        if (context == null) return new Rect();
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                var metrics = wm.getCurrentWindowMetrics();
                Rect out = new Rect(metrics.getBounds());
                var insets = metrics.getWindowInsets()
                        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                out.left += insets.left;
                out.top += insets.top;
                out.right -= insets.right;
                out.bottom -= insets.bottom;
                if (!out.isEmpty()) return out;
            }
        } catch (Throwable ignored) { }
        Rect display = displayBounds(context);
        if (!display.isEmpty()) return display;
        try {
            return new Rect(0, 0,
                    context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels);
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

    static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private ScreenGeometry() {}
}
