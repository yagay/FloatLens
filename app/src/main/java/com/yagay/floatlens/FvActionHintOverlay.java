package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * FV-style 24dp action indicator.
 *
 * The Window itself never changes size or jumps to ProbePoint. PLUS and DOT are only drawable
 * states inside the same 24dp CircleImageView-equivalent container. Its side is supplied from the
 * gesture snapshot and remains fixed for that pointer stream, matching FloatIconView.V()/D4().
 */
public final class FvActionHintOverlay {
    public enum Mode { PLUS, DOT }

    private static final float FV_WINDOW_SIZE_DP = 24f;
    private static final float FV_DOT_DIAMETER_DP = 15f;

    private final Context context;
    private final WindowManager wm;
    private final int windowSizePx;
    private final int dotDiameterPx;
    private final HintView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;
    private Mode mode = Mode.PLUS;

    public FvActionHintOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        windowSizePx = Math.max(1, Math.round(FV_WINDOW_SIZE_DP * density));
        dotDiameterPx = Math.max(1, Math.round(FV_DOT_DIAMETER_DP * density));
        view = new HintView(context, dotDiameterPx);
        view.setVisibility(View.INVISIBLE);
        lp = new WindowManager.LayoutParams(
                windowSizePx,
                windowSizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -windowSizePx;
        lp.y = 0;
        attachHidden();
    }

    private void attachHidden() {
        if (attached) return;
        try {
            wm.addView(view, lp);
            attached = true;
            DiagnosticLog.i(context, "FV_INDICATOR", "ATTACH window=" + windowSizePx
                    + " dotGlyph=" + dotDiameterPx);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_INDICATOR", "attach failed=" + t);
        }
    }

    public void showPlusNextTo(float iconLeft, float iconTop, float iconWidth,
                               float iconHeight, boolean leftSide) {
        showStateNextTo(Mode.PLUS, iconLeft, iconTop, iconWidth, iconHeight, leftSide);
    }

    public void showDotNextTo(float iconLeft, float iconTop, float iconWidth,
                              float iconHeight, boolean leftSide) {
        showStateNextTo(Mode.DOT, iconLeft, iconTop, iconWidth, iconHeight, leftSide);
    }

    private void showStateNextTo(Mode next, float iconLeft, float iconTop, float iconWidth,
                                 float iconHeight, boolean leftSide) {
        if (!attached) attachHidden();
        if (!attached) return;

        setMode(next);
        // FV D4(): position is always derived from the floating icon and the fixed V() side state.
        // PLUS -> DOT therefore changes only the drawable; the top-left remains continuous.
        lp.x = Math.round(leftSide ? iconLeft + iconWidth : iconLeft - windowSizePx);
        lp.y = Math.round(iconTop - windowSizePx);
        updateVisible(next + " side=" + (leftSide ? "L" : "R")
                + " icon=" + Math.round(iconLeft) + "," + Math.round(iconTop));
    }

    private void setMode(Mode next) {
        if (next == null) next = Mode.PLUS;
        if (mode == next) return;
        mode = next;
        view.setMode(next);
        DiagnosticLog.i(context, "FV_INDICATOR", "STATE " + next
                + " windowSize=" + windowSizePx);
    }

    private void updateVisible(String detail) {
        try {
            wm.updateViewLayout(view, lp);
            if (!visible) {
                view.setVisibility(View.VISIBLE);
                visible = true;
            }
            DiagnosticLog.i(context, "FV_INDICATOR", detail + " window=" + lp.x + "," + lp.y
                    + " size=" + windowSizePx);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_INDICATOR", "move failed=" + t);
        }
    }

    public void hide() {
        if (!attached || !visible) return;
        visible = false;
        view.setVisibility(View.INVISIBLE);
        lp.x = -windowSizePx;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        DiagnosticLog.i(context, "FV_INDICATOR", "HIDE mode=" + mode);
    }

    public void close() {
        visible = false;
        if (attached) {
            try { wm.removeView(view); } catch (Throwable ignored) {}
        }
        attached = false;
    }

    private static final class HintView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int dotDiameterPx;
        private Mode mode = Mode.PLUS;

        HintView(Context c, int dotDiameterPx) {
            super(c);
            this.dotDiameterPx = dotDiameterPx;
            fill.setStyle(Paint.Style.FILL);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, 1.5f * getResources().getDisplayMetrics().density));
            border.setColor(Color.WHITE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeWidth(Math.max(2f, 2f * getResources().getDisplayMetrics().density));
            glyph.setColor(Color.WHITE);
            setBackgroundColor(Color.TRANSPARENT);
        }

        void setMode(Mode value) {
            if (value == null) value = Mode.PLUS;
            if (mode == value) return;
            mode = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            if (mode == Mode.DOT) {
                // FV swaps the Drawable in the same 24dp CircleImageView. Keep the window fixed and
                // render the smaller dot glyph centred inside it instead of resizing/repositioning.
                float r = Math.max(1f, dotDiameterPx / 2f - border.getStrokeWidth());
                fill.setColor(0xDD1976D2);
                canvas.drawCircle(cx, cy, r, fill);
                canvas.drawCircle(cx, cy, r, border);
                return;
            }

            float r = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - border.getStrokeWidth());
            fill.setColor(0xE62B2D31);
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, border);
            float d = r * .38f;
            canvas.drawLine(cx - d, cy, cx + d, cy, glyph);
            canvas.drawLine(cx, cy - d, cx, cy + d, glyph);
        }
    }
}
