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

/** FloatLens probe point; geometry follows the behavior observed in FV m2/g. */
public final class FlProbePointOverlay {
    private static final float FL_PROBE_SIZE_DP = 15f;

    private final Context context;
    private final FlOverlayWindowHost windowHost;
    private final int sizePx;
    private final ProbeView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;
    private int targetX, targetY;
    private SelectionVisualState state = SelectionVisualState.TRACKING;

    public FlProbePointOverlay(Context c) {
        context = c.getApplicationContext();
        windowHost = new FlOverlayWindowHost(context);
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        sizePx = Math.max(1, Math.round(FL_PROBE_SIZE_DP * density));
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

    public void setTracking() { setVisualState(SelectionVisualState.TRACKING); }
    public void setReady() { setVisualState(SelectionVisualState.READY); }
    public SelectionVisualState state() { return state; }

    public void setVisualState(SelectionVisualState next) {
        if (next == null) next = SelectionVisualState.TRACKING;
        if (state == next) return;
        state = next;
        view.setState(next);
        DiagnosticLog.i(context, "FL_PROBE_VIEW", "STATE " + next);
    }

    public PointF showAt(float screenX, float screenY) {
        targetX = Math.round(screenX - sizePx / 2f);
        targetY = Math.round(screenY - sizePx / 2f);
        if (!attached) {
            lp.x = targetX;
            lp.y = targetY;
            attached = windowHost.add(view, lp, "probe");
            if (attached) {
                visible = true;
                DiagnosticLog.i(context, "FL_PROBE_VIEW",
                        "ATTACH size=" + sizePx + " window=" + lp.x + "," + lp.y
                                + " state=" + state
                                + " accessibilityHost=" + windowHost.isAccessibilityHosted());
            }
        } else {
            if (!visible) {
                visible = true;
                view.setVisibility(View.VISIBLE);
            }
            if (lp.x != targetX || lp.y != targetY) {
                lp.x = targetX;
                lp.y = targetY;
                windowHost.update(view, lp, "probe");
            }
        }
        float cx = targetX + sizePx / 2f;
        float cy = targetY + sizePx / 2f;
        DiagnosticLog.i(context, "FL_PROBE_VIEW",
                "MOVE centre=" + Math.round(cx) + "," + Math.round(cy)
                        + " window=" + targetX + "," + targetY + " state=" + state);
        return new PointF(cx, cy);
    }

    public int windowX() { return targetX; }
    public int windowY() { return targetY; }
    public int windowSizePx() { return sizePx; }
    public boolean isAttached() { return attached; }

    public void hide() {
        close();
        DiagnosticLog.i(context, "FL_PROBE_VIEW", "HIDE state=" + state);
    }

    public void close() {
        visible = false;
        if (attached) windowHost.remove(view, "probe");
        attached = false;
    }

    private static final class ProbeView extends View {
        private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        private SelectionVisualState state = SelectionVisualState.TRACKING;

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

        void setState(SelectionVisualState value) {
            if (value == null) value = SelectionVisualState.TRACKING;
            if (state == value) return;
            state = value;
            updateColor();
            invalidate();
        }

        private void updateColor() {
            plus.setColor(SelectionVisuals.frameColor(state));
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
