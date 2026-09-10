package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Converts the finger MotionEvent into the floating icon's actual selection hotspot.
 *
 * FV's v3() works from floating-icon/selection geometry rather than blindly using raw finger
 * coordinates. The exact private selection-layer edge interpolation is intentionally not guessed
 * here; this clean-room implementation preserves the confirmed invariant that the selected point
 * follows the icon geometry, so touching the icon off-centre does not shift image/View targeting.
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

        // Derive the icon's temporary top-left from the raw finger point and where inside the icon
        // the user originally touched, then use the icon centre as the screen-space selection point.
        float iconLeft = e.getRawX() - touchOffsetX;
        float iconTop = e.getRawY() - touchOffsetY;
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
