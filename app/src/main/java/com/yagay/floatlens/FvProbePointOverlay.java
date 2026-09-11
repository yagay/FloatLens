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
 * FV selection probe (FooViewService M/N/O/Q).
 *
 * FV's own help text describes the interaction explicitly: while the icon is moving the selection
 * cross is red; after the move-idle selection delay fires, the cross turns yellow. The same 15dp
 * probe Window remains centred on the transformed hit Point in both states.
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
    private boolean framePosted;
    private int targetX,targetY;
    private final Runnable applyMove = this::applyPendingMove;
    private State state = State.TRACKING_RED;

    public FvProbePointOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        sizePx = Math.max(1, Math.round(FV_PROBE_SIZE_DP * density));
        view = new ProbeView(context);
        view.setState(state);
        view.setVisibility(View.INVISIBLE);
        lp = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -sizePx;
        lp.y = 0;
        attachHidden();
    }

    private void attachHidden() {
        if (attached) return;
        try {
            wm.addView(view, lp);
            attached = true;
            DiagnosticLog.i(context, "FV_PROBE_VIEW", "ATTACH size=" + sizePx + " state=" + state);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_PROBE_VIEW", "attach failed=" + t);
        }
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

    /** Mirrors FooViewService.e4(Point): O.x=p.x-Q/2, O.y=p.y-Q/2. */
    public PointF showAt(float screenX, float screenY) {
        if (!attached) attachHidden();
        targetX = Math.round(screenX - sizePx / 2f);
        targetY = Math.round(screenY - sizePx / 2f);
        if (attached) {
            if (!visible) { visible = true; view.setVisibility(View.VISIBLE); }
            if (!framePosted) { framePosted = true; view.postOnAnimation(applyMove); }
        }
        float cx = targetX + sizePx / 2f;
        float cy = targetY + sizePx / 2f;
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "MOVE centre=" + Math.round(cx) + ","
                + Math.round(cy) + " window=" + targetX + "," + targetY + " state=" + state);
        return new PointF(cx, cy);
    }

    private void applyPendingMove() {
        framePosted = false;
        if (!attached) return;
        if (lp.x == targetX && lp.y == targetY) return;
        lp.x = targetX; lp.y = targetY;
        try { wm.updateViewLayout(view, lp); }
        catch (Throwable t) { DiagnosticLog.i(context, "FV_PROBE_VIEW", "move failed=" + t); }
    }

    /** Mirrors FV F3(): hide the ImageView and move its tiny Window off-screen. */
    public void hide() {
        if (!attached) return;
        visible = false;
        framePosted = false;
        try { view.removeCallbacks(applyMove); } catch (Throwable ignored) {}
        view.setVisibility(View.INVISIBLE);
        lp.x = -sizePx;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "HIDE state=" + state);
    }

    public void close() {
        visible = false;
        framePosted = false;
        try { view.removeCallbacks(applyMove); } catch (Throwable ignored) {}
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
            plus.setShadowLayer(Math.max(1f, 1.25f * getResources().getDisplayMetrics().density), 0f,
                    Math.max(.5f, .5f * getResources().getDisplayMetrics().density), 0x77000000);
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
