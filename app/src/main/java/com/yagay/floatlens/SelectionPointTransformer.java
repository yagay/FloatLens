package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Converts the finger stream into the floating icon's screen-space selection hotspot.
 *
 * The touch offset is captured while FloatIconView is still the small floating window. Once the
 * same View is expanded to MATCH_PARENT for FV-style direct selection, MotionEvent local x/y change
 * coordinate spaces, but raw x/y remain stable. All later selection therefore uses raw coordinates
 * plus the original in-icon offset.
 */
public final class SelectionPointTransformer {
    private final Context context;
    private final float iconWidth;
    private final float iconHeight;
    private float touchOffsetX;
    private float touchOffsetY;
    private boolean initialized;

    public SelectionPointTransformer(Context c, float iconWidth, float iconHeight) {
        context = c.getApplicationContext();
        this.iconWidth = Math.max(1f, iconWidth);
        this.iconHeight = Math.max(1f, iconHeight);
    }

    public void begin(MotionEvent e) {
        if (e == null) return;
        touchOffsetX = e.getX();
        touchOffsetY = e.getY();
        initialized = true;
    }

    public PointF transform(MotionEvent e) {
        if (e == null) return new PointF();
        if (!initialized) begin(e);
        return transformRaw(e.getRawX(), e.getRawY());
    }

    /** Use after FloatIconView has been expanded to full screen. */
    public PointF transformRaw(float rawX, float rawY) {
        float iconLeft = rawX - touchOffsetX;
        float iconTop = rawY - touchOffsetY;
        float x = iconLeft + iconWidth / 2f;
        float y = iconTop + iconHeight / 2f;

        Rect screen = screenBounds();
        if (!screen.isEmpty()) {
            x = Math.max(screen.left, Math.min(x, screen.right - 1f));
            y = Math.max(screen.top, Math.min(y, screen.bottom - 1f));
        }
        return new PointF(x, y);
    }

    private Rect screenBounds() {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        } catch (Throwable t) {
            return new Rect();
        }
    }
}
