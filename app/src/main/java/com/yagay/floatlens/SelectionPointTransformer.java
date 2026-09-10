package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;

/** Converts the original floating-icon touch stream into FV-style coordinates. */
public final class SelectionPointTransformer {
    private static final float FV_EDGE_LEAD_DP = 10f;
    private static final float FV_X_PROBE_OFFSET_DP = 25f;
    private static final float FV_EDGE_SPAN_DP = 50f;
    private static final float FV_Y_HELPER_INSET_DP = 20f;
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
    private long lastLogAt;

    public SelectionPointTransformer(Context c, float iconWidth, float iconHeight) {
        context = c.getApplicationContext();
        this.iconWidth = Math.max(1f, iconWidth);
        this.iconHeight = Math.max(1f, iconHeight);
        density = Math.max(0.1f, context.getResources().getDisplayMetrics().density);
    }

    /**
     * Snapshot FV's gesture origin while the icon is still the small overlay.
     * The helper side is deliberately locked here and is never recalculated from screen centre
     * during MOVE. This mirrors FloatIconView.V(): one side state for the whole pointer stream.
     */
    public void begin(MotionEvent e) {
        if (e == null) return;
        touchOffsetX = e.getX();
        touchOffsetY = e.getY();
        downRawX = e.getRawX();
        downRawY = e.getRawY();
        startIconLeft = downRawX - touchOffsetX;
        startIconTop = downRawY - touchOffsetY;

        Rect screen = screenBounds();
        gestureLeftSide = screen.isEmpty()
                || startIconLeft + iconWidth / 2f < screen.exactCenterX();
        initialized = true;

        DiagnosticLog.i(context, "FV_PROBE", "BEGIN raw="
                + Math.round(downRawX) + "," + Math.round(downRawY)
                + " local=" + Math.round(touchOffsetX) + "," + Math.round(touchOffsetY)
                + " startIcon=" + Math.round(startIconLeft) + "," + Math.round(startIconTop)
                + " side=" + (gestureLeftSide ? "L" : "R")
                + " icon=" + Math.round(iconWidth) + "x" + Math.round(iconHeight)
                + " density=" + String.format(java.util.Locale.US, "%.3f", density)
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

    /**
     * Reconstruct FV's virtual small-icon bounds from the gesture origin plus absolute raw delta.
     * This remains stable after the real FloatIconView is expanded to MATCH_PARENT.
     */
    public RectF iconBoundsForRaw(float rawX, float rawY) {
        ensureInitializedFallback();
        float left = startIconLeft + (rawX - downRawX);
        float top = startIconTop + (rawY - downRawY);
        return new RectF(left, top, left + iconWidth, top + iconHeight);
    }

    /** Use after FloatIconView has been expanded to full screen. */
    public PointF transformRaw(float rawX, float rawY) {
        ensureInitializedFallback();

        final Rect screen = screenBounds();
        final float lead = dp(FV_EDGE_LEAD_DP);
        final float edgeSpan = dp(FV_EDGE_SPAN_DP);
        final float helperInset = dp(FV_Y_HELPER_INSET_DP);
        final float iconLeft = startIconLeft + (rawX - downRawX);

        // FV normal X path reconstructed from its starting icon position + raw pointer delta.
        float x = iconLeft - dp(FV_X_PROBE_OFFSET_DP);
        boolean rightCompensation = false;
        float rightThreshold = Float.NaN;

        if (!screen.isEmpty()) {
            final float edgeX = rawX + lead;
            rightThreshold = screen.right - iconWidth - edgeSpan - lead;
            if (edgeX > rightThreshold) {
                x += edgeX - rightThreshold;
                rightCompensation = true;
            }
        }

        // FV v3() normal Y: rawY + 10dp - 20dp - 50dp.
        final float edgeY = rawY + lead;
        float y = edgeY - helperInset - edgeSpan;
        boolean bottomCompensation = false;
        float bottomThreshold = Float.NaN;

        if (!screen.isEmpty()) {
            bottomThreshold = screen.bottom - helperInset - edgeSpan;
            if (edgeY > bottomThreshold) {
                y = 2f * edgeY - screen.bottom;
                bottomCompensation = true;
            }
            if (y > screen.bottom) y = screen.bottom;
        }

        maybeLog(rawX, rawY, iconLeft, x, y,
                rightCompensation, bottomCompensation, rightThreshold, bottomThreshold, screen);
        return new PointF(x, y);
    }

    private void ensureInitializedFallback() {
        if (initialized) return;
        Rect screen = screenBounds();
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
                          boolean rightCompensation, boolean bottomCompensation,
                          float rightThreshold, float bottomThreshold, Rect screen) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS && !rightCompensation && !bottomCompensation) return;
        lastLogAt = now;
        DiagnosticLog.i(context, "FV_PROBE", "raw=" + Math.round(rawX) + "," + Math.round(rawY)
                + " iconLeft=" + Math.round(iconLeft)
                + " side=" + (gestureLeftSide ? "L" : "R")
                + " probe=" + Math.round(x) + "," + Math.round(y)
                + " edgeR=" + rightCompensation + " edgeB=" + bottomCompensation
                + " thresholdR=" + (Float.isNaN(rightThreshold) ? "n/a" : Math.round(rightThreshold))
                + " thresholdB=" + (Float.isNaN(bottomThreshold) ? "n/a" : Math.round(bottomThreshold))
                + " screen=" + screen);
    }

    private float dp(float value) { return value * density; }

    private Rect screenBounds() {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        } catch (Throwable t) {
            return new Rect();
        }
    }
}
