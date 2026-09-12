package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * FV m2/g float_pen_view equivalent.
 *
 * The original APK creates a 15dp x 15dp WindowManager window (m2/g.x) and moves it immediately.
 * pointer_op_hint is a second sibling window positioned from this exact window:
 *   hint.x = pen.x + 15dp
 *   hint.y = pen.y - 20dp
 *
 * Keep these coordinates available to FvPointerOperationHintOverlay; do not derive the hint from
 * the floating owner icon or from raw touch coordinates.
 */
public final class FvProbePointOverlay {
    public enum State { TRACKING_RED, READY_YELLOW }

    private static final float FV_PROBE_SIZE_DP = 15f;
    private static final int FV_TRACKING_RED = 0xFFFF0000;
    private static final int FV_READY_YELLOW = 0xFFFFFF00;

    private final Context context;
    private final WindowManager wm;
    private final int sizePx;
    private final ProbeView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;
    private int targetX, targetY;
    private State state = State.TRACKING_RED;

    public FvProbePointOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        sizePx = Math.max(1, Math.round(FV_PROBE_SIZE_DP * density));

        view = new ProbeView(context);
        view.setState(state);

        lp = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -sizePx;
        lp.y = 0;
    }

    /** Red while following/moving before FV's delayed selection state has fired. */
    public void setTracking() { setState(State.TRACKING_RED); }

    /** Yellow once the move-idle/direct-selection state has fired. */
    public void setReady() { setState(State.READY_YELLOW); }

    public State state() { return state; }

    private void setState(State next) {
        if (next == null) next = State.TRACKING_RED;
        if (state == next) return;
        state = next;
        view.setState(next);
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "STATE " + next);
    }

    /**
     * Mirrors FV m2/g: x = point.x - c0/2, y = point.y - c0/2, then WindowManager is updated
     * immediately. FV does not defer this move to the next animation frame.
     */
    public PointF showAt(float screenX, float screenY) {
        targetX = Math.round(screenX - sizePx / 2f);
        targetY = Math.round(screenY - sizePx / 2f);

        if (!attached) {
            lp.x = targetX;
            lp.y = targetY;
            try {
                wm.addView(view, lp);
                attached = true;
                visible = true;
                DiagnosticLog.i(context, "FV_PROBE_VIEW",
                        "ATTACH size=" + sizePx + " window=" + lp.x + "," + lp.y
                                + " state=" + state);
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_PROBE_VIEW", "attach failed=" + t);
            }
        } else {
            if (!visible) {
                visible = true;
                view.setVisibility(View.VISIBLE);
            }
            if (lp.x != targetX || lp.y != targetY) {
                lp.x = targetX;
                lp.y = targetY;
                try {
                    wm.updateViewLayout(view, lp);
                } catch (Throwable t) {
                    DiagnosticLog.i(context, "FV_PROBE_VIEW", "move failed=" + t);
                }
            }
        }

        float cx = targetX + sizePx / 2f;
        float cy = targetY + sizePx / 2f;
        DiagnosticLog.i(context, "FV_PROBE_VIEW",
                "MOVE centre=" + Math.round(cx) + "," + Math.round(cy)
                        + " window=" + targetX + "," + targetY + " state=" + state);
        return new PointF(cx, cy);
    }

    /** Exact current m2/g.x WindowManager coordinates used by pointer_op_hint. */
    public int windowX() { return targetX; }
    public int windowY() { return targetY; }
    public int windowSizePx() { return sizePx; }
    public boolean isAttached() { return attached; }

    /**
     * m2/g.s() removes the pen window rather than parking it off-screen. Keeping the same lifecycle
     * also preserves sibling Z-order with pointer_op_hint when selection starts again.
     */
    public void hide() {
        close();
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "HIDE state=" + state);
    }

    public void close() {
        visible = false;
        if (attached) {
            try { wm.removeView(view); } catch (Throwable ignored) {}
        }
        attached = false;
    }

    private static final class ProbeView extends View {
        private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        private State state = State.TRACKING_RED;

        ProbeView(Context c) {
            super(c);
            plus.setStyle(Paint.Style.STROKE);
            plus.setStrokeCap(Paint.Cap.SQUARE);
            plus.setStrokeWidth(Math.max(1f, 1.8f * getResources().getDisplayMetrics().density));
            plus.setShadowLayer(
                    Math.max(1f, 1.25f * getResources().getDisplayMetrics().density),
                    0f,
                    Math.max(.5f, .5f * getResources().getDisplayMetrics().density),
                    0x77000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setBackgroundColor(Color.TRANSPARENT);
            updateColor();
        }

        void setState(State value) {
            if (value == null) value = State.TRACKING_RED;
            if (state == value) return;
            state = value;
            updateColor();
            invalidate();
        }

        private void updateColor() {
            plus.setColor(state == State.READY_YELLOW ? FV_READY_YELLOW : FV_TRACKING_RED);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = Math.min(getWidth(), getHeight()) * .34f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plus);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plus);
        }
    }
}
