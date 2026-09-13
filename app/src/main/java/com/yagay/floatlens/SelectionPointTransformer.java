package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;

/** Converts the original floating-icon touch stream into FV-style probe coordinates. */
public final class SelectionPointTransformer {
    private static final float FL_X_PROBE_GAP_DP = 25f;
    private static final float FL_Y_PROBE_OFFSET_DP = 60f;
    private static final long LOG_INTERVAL_MS = 80L;

    private final Context context;
    private final float iconWidth;
    private final float iconHeight;
    private final float density;

    private float touchOffsetX;
    private float touchOffsetY;
    private float downRawX;
    private float downRawY;
    private float startIconLeft;
    private float startIconTop;
    private boolean gestureLeftSide;
    private boolean initialized;
    private final Rect gestureScreen = new Rect();
    private long lastLogAt;

    public SelectionPointTransformer(Context c, float iconWidth, float iconHeight) {
        context = c.getApplicationContext();
        this.iconWidth = Math.max(1f, iconWidth);
        this.iconHeight = Math.max(1f, iconHeight);
        density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
    }

    /**
     * FV starts from the FloatIconView's actual current Window position, including a partially hidden
     * edge position. raw-local gives that real overlay origin before the View is ever expanded.
     */
    public void begin(MotionEvent e) {
        if (e == null) return;
        touchOffsetX = e.getX();
        touchOffsetY = e.getY();
        downRawX = e.getRawX();
        downRawY = e.getRawY();
        startIconLeft = downRawX - touchOffsetX;
        startIconTop = downRawY - touchOffsetY;

        Rect screen = currentScreenBounds();
        gestureScreen.set(screen);
        gestureLeftSide = screen.isEmpty()
                || startIconLeft + iconWidth / 2f < screen.exactCenterX();
        initialized = true;

        DiagnosticLog.i(context, "FL_PROBE", "BEGIN raw="
                + Math.round(downRawX) + "," + Math.round(downRawY)
                + " local=" + Math.round(touchOffsetX) + "," + Math.round(touchOffsetY)
                + " startIcon=" + Math.round(startIconLeft) + "," + Math.round(startIconTop)
                + " side=" + (gestureLeftSide ? "L" : "R")
                + " icon=" + Math.round(iconWidth) + "x" + Math.round(iconHeight)
                + " screen=" + screen);
    }

    public PointF transform(MotionEvent e) {
        if (e == null) return new PointF();
        if (!initialized) begin(e);
        return transformRaw(e.getRawX(), e.getRawY());
    }

    public boolean gestureLeftSide() {
        ensureInitializedFallback();
        return gestureLeftSide;
    }

    /** Exact virtual small-icon trajectory: startWindow + currentRaw - downRaw. */
    public RectF iconBoundsForRaw(float rawX, float rawY) {
        ensureInitializedFallback();
        float left = startIconLeft + (rawX - downRawX);
        float top = startIconTop + (rawY - downRawY);
        return new RectF(left, top, left + iconWidth, top + iconHeight);
    }

    /**
     * FV keeps the probe on the inward side of the floating icon. The old reconstruction applied a
     * right-edge compensation that could produce x=1478 on a 1272px display, and on the left edge it
     * could produce negative coordinates. Both make Accessibility hit-testing impossible.
     */
    public PointF transformRaw(float rawX, float rawY) {
        ensureInitializedFallback();

        Rect screen = gestureScreen;
        float iconLeft = startIconLeft + (rawX - downRawX);
        float gap = dp(FL_X_PROBE_GAP_DP);

        // Keep the probe between the edge-mounted icon and the page content.
        float x = gestureLeftSide
                ? iconLeft + iconWidth + gap
                : iconLeft - gap;
        float y = rawY - dp(FL_Y_PROBE_OFFSET_DP);

        boolean clampedX = false;
        boolean clampedY = false;
        if (!screen.isEmpty()) {
            float minX = screen.left;
            float maxX = Math.max(minX, screen.right - 1f);
            float minY = screen.top;
            float maxY = Math.max(minY, screen.bottom - 1f);
            float safeX = Math.max(minX, Math.min(maxX, x));
            float safeY = Math.max(minY, Math.min(maxY, y));
            clampedX = safeX != x;
            clampedY = safeY != y;
            x = safeX;
            y = safeY;
        }

        maybeLog(rawX, rawY, iconLeft, x, y, clampedX, clampedY, screen);
        return new PointF(x, y);
    }

    private void ensureInitializedFallback() {
        if (initialized) return;
        Rect screen = currentScreenBounds();
        gestureScreen.set(screen);
        touchOffsetX = iconWidth / 2f;
        touchOffsetY = iconHeight / 2f;
        downRawX = screen.isEmpty() ? iconWidth / 2f : screen.exactCenterX();
        downRawY = screen.isEmpty() ? iconHeight / 2f : screen.exactCenterY();
        startIconLeft = downRawX - touchOffsetX;
        startIconTop = downRawY - touchOffsetY;
        gestureLeftSide = screen.isEmpty()
                || startIconLeft + iconWidth / 2f < screen.exactCenterX();
        initialized = true;
    }

    private void maybeLog(float rawX, float rawY, float iconLeft, float x, float y,
                          boolean clampedX, boolean clampedY, Rect screen) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS && !clampedX && !clampedY) return;
        lastLogAt = now;
        DiagnosticLog.i(context, "FL_PROBE", "raw=" + Math.round(rawX) + "," + Math.round(rawY)
                + " iconLeft=" + Math.round(iconLeft)
                + " side=" + (gestureLeftSide ? "L" : "R")
                + " probe=" + Math.round(x) + "," + Math.round(y)
                + " clampX=" + clampedX + " clampY=" + clampedY
                + " screen=" + screen);
    }

    private float dp(float value) { return value * density; }

    private Rect currentScreenBounds() {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        } catch (Throwable t) {
            return new Rect();
        }
    }
}
