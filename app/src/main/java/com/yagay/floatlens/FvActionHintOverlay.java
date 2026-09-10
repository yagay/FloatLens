package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * One FV-style drag indicator whose visual state changes during the same pointer stream.
 *
 * PLUS: 24dp action hint beside the moving floating icon.
 * DOT: 15dp probe indicator centred on the transformed View hit-test point.
 *
 * The same View/WindowManager.LayoutParams instance is retained across the transition. This mirrors
 * the user-visible FV behaviour: the helper does not appear as two independent icons in different
 * places; one indicator changes shape/size/position as the gesture enters selection mode.
 */
public final class FvActionHintOverlay {
    public enum Mode { PLUS, DOT }

    private static final float FV_PLUS_SIZE_DP = 24f;
    private static final float FV_DOT_SIZE_DP = 15f;

    private final Context context;
    private final WindowManager wm;
    private final int plusSizePx;
    private final int dotSizePx;
    private final HintView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;
    private Mode mode = Mode.PLUS;

    public FvActionHintOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        plusSizePx = Math.max(1, Math.round(FV_PLUS_SIZE_DP * density));
        dotSizePx = Math.max(1, Math.round(FV_DOT_SIZE_DP * density));
        view = new HintView(context);
        view.setVisibility(View.INVISIBLE);
        lp = new WindowManager.LayoutParams(
                plusSizePx,
                plusSizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -plusSizePx;
        lp.y = 0;
        attachHidden();
    }

    private void attachHidden() {
        if (attached) return;
        try {
            wm.addView(view, lp);
            attached = true;
            DiagnosticLog.i(context, "FV_INDICATOR", "ATTACH plus=" + plusSizePx + " dot=" + dotSizePx);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_INDICATOR", "attach failed=" + t);
        }
    }

    public void showNextTo(View icon) {
        if (icon == null) return;
        int[] loc = new int[2];
        try { icon.getLocationOnScreen(loc); } catch (Throwable t) { return; }
        showPlusNextTo(loc[0], loc[1], icon.getWidth(), icon.getHeight());
    }

    /** Drag phase: the same indicator is a 24dp PLUS beside the moving icon. */
    public void showNextTo(float iconLeft, float iconTop, float iconWidth, float iconHeight) {
        showPlusNextTo(iconLeft, iconTop, iconWidth, iconHeight);
    }

    public void showPlusNextTo(float iconLeft, float iconTop, float iconWidth, float iconHeight) {
        if (!attached) attachHidden();
        if (!attached) return;

        setMode(Mode.PLUS);
        Rect screen = screenBounds();
        float iconCenter = iconLeft + iconWidth / 2f;
        float screenCenter = screen.isEmpty() ? iconCenter : screen.exactCenterX();
        boolean leftSide = iconCenter < screenCenter;

        lp.x = Math.round(leftSide ? iconLeft + iconWidth : iconLeft - plusSizePx);
        lp.y = Math.round(iconTop - plusSizePx);
        updateVisible("PLUS side=" + (leftSide ? "L" : "R")
                + " icon=" + Math.round(iconLeft) + "," + Math.round(iconTop));
    }

    /** Selection phase: the very same indicator becomes a 15dp DOT at ProbePoint. */
    public void showDotAt(float screenX, float screenY) {
        if (!attached) attachHidden();
        if (!attached) return;

        setMode(Mode.DOT);
        lp.x = Math.round(screenX - dotSizePx / 2f);
        lp.y = Math.round(screenY - dotSizePx / 2f);
        updateVisible("DOT centre=" + Math.round(screenX) + "," + Math.round(screenY));
    }

    private void setMode(Mode next) {
        if (next == null) next = Mode.PLUS;
        int size = next == Mode.DOT ? dotSizePx : plusSizePx;
        if (mode != next || lp.width != size || lp.height != size) {
            mode = next;
            lp.width = size;
            lp.height = size;
            view.setMode(next);
            DiagnosticLog.i(context, "FV_INDICATOR", "STATE " + next + " size=" + size);
        }
    }

    private void updateVisible(String detail) {
        try {
            wm.updateViewLayout(view, lp);
            if (!visible) {
                view.setVisibility(View.VISIBLE);
                visible = true;
            }
            DiagnosticLog.i(context, "FV_INDICATOR", detail + " window=" + lp.x + "," + lp.y
                    + " size=" + lp.width);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_INDICATOR", "move failed=" + t);
        }
    }

    public void hide() {
        if (!attached) return;
        if (!visible) return;
        visible = false;
        view.setVisibility(View.INVISIBLE);
        int size = Math.max(plusSizePx, dotSizePx);
        lp.x = -size;
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

    private Rect screenBounds() {
        try { return new Rect(wm.getCurrentWindowMetrics().getBounds()); }
        catch (Throwable t) { return new Rect(); }
    }

    private static final class HintView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Mode mode = Mode.PLUS;

        HintView(Context c) {
            super(c);
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
            float r = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - border.getStrokeWidth());

            if (mode == Mode.DOT) {
                fill.setColor(0xDD1976D2);
                canvas.drawCircle(cx, cy, r, fill);
                canvas.drawCircle(cx, cy, r, border);
                return;
            }

            fill.setColor(0xE62B2D31);
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, border);
            float d = r * .38f;
            canvas.drawLine(cx - d, cy, cx + d, cy, glyph);
            canvas.drawLine(cx, cy - d, cx, cy + d, glyph);
        }
    }
}
