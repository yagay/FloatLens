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
 * Lightweight FV-style rectangular selection indicator.
 * Four 2dp non-touchable edge windows reproduce FV's single red/yellow stroke without a
 * fullscreen translucent surface. The FloatLens size label is a fifth independent window and is
 * always placed outside the selected rectangle so it cannot be captured with the selected area.
 */
final class FvRegionFrameOverlay {
    private final Context context;
    private final WindowManager wm;
    private final int borderPx;
    private final int labelHeightPx;
    private final int labelGapPx;
    private final int labelMaxWidthPx;
    private final EdgeView top;
    private final EdgeView bottom;
    private final EdgeView left;
    private final EdgeView right;
    private final LabelView label;
    private final WindowManager.LayoutParams topLp;
    private final WindowManager.LayoutParams bottomLp;
    private final WindowManager.LayoutParams leftLp;
    private final WindowManager.LayoutParams rightLp;
    private final WindowManager.LayoutParams labelLp;
    private final Rect pending = new Rect();
    private boolean attached;
    private boolean confirmed;
    private boolean framePosted;
    private final Runnable applyRunnable = this::applyPending;

    FvRegionFrameOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float d = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        borderPx = SelectionVisuals.edgeThicknessPx(context);
        labelHeightPx = Math.max(1, Math.round(22f * d));
        labelGapPx = Math.max(1, Math.round(4f * d));
        labelMaxWidthPx = Math.max(1, Math.round(150f * d));
        top = new EdgeView(context, false);
        bottom = new EdgeView(context, false);
        left = new EdgeView(context, false);
        right = new EdgeView(context, false);
        label = new LabelView(context);
        topLp = lp(1, borderPx);
        bottomLp = lp(1, borderPx);
        leftLp = lp(borderPx, 1);
        rightLp = lp(borderPx, 1);
        labelLp = lp(1, labelHeightPx);
        applyConfirmed(false);
    }

    void show(Rect screenRect) {
        if (screenRect == null || screenRect.width() < 2 || screenRect.height() < 2) return;
        pending.set(screenRect);
        ensureAttached();
        if (!attached || framePosted) return;
        framePosted = true;
        top.postOnAnimation(applyRunnable);
    }

    /** FV switches the same 2dp stroke from red to yellow once the target is confirmed. */
    void setConfirmed(boolean value) {
        if (confirmed == value) return;
        confirmed = value;
        applyConfirmed(value);
    }

    private void applyConfirmed(boolean value) {
        top.setConfirmed(value);
        bottom.setConfirmed(value);
        left.setConfirmed(value);
        right.setConfirmed(value);
        label.setAccentColor(SelectionVisuals.frameColor(value));
    }

    void close() {
        framePosted = false;
        try { top.removeCallbacks(applyRunnable); } catch (Throwable ignored) {}
        if (!attached) return;
        remove(top); remove(bottom); remove(left); remove(right); remove(label);
        attached = false;
        pending.setEmpty();
    }

    private void ensureAttached() {
        if (attached) return;
        try {
            top.setVisibility(View.INVISIBLE);
            bottom.setVisibility(View.INVISIBLE);
            left.setVisibility(View.INVISIBLE);
            right.setVisibility(View.INVISIBLE);
            label.setVisibility(View.INVISIBLE);
            wm.addView(top, topLp);
            wm.addView(bottom, bottomLp);
            wm.addView(left, leftLp);
            wm.addView(right, rightLp);
            wm.addView(label, labelLp);
            attached = true;
            DiagnosticLog.i(context, "FV_REGION_FRAME", "ATTACH fv-single-stroke label-outside");
        } catch (Throwable t) {
            remove(top); remove(bottom); remove(left); remove(right); remove(label);
            attached = false;
            DiagnosticLog.i(context, "FV_REGION_FRAME", "attach failed=" + t);
        }
    }

    private void applyPending() {
        framePosted = false;
        if (!attached || pending.isEmpty()) return;
        int l = pending.left, t = pending.top, r = pending.right, b = pending.bottom;
        int w = Math.max(borderPx, r - l);
        int h = Math.max(borderPx, b - t);

        // FV uses one 2dp STROKE rectangle. Four tiny windows reproduce the same appearance while
        // keeping video/animations beneath the selected View unobstructed.
        update(top, topLp, l, t, w, borderPx);
        update(bottom, bottomLp, l, Math.max(t, b - borderPx), w, borderPx);
        int sideTop = t + borderPx;
        int sideH = Math.max(1, h - borderPx * 2);
        update(left, leftLp, l, sideTop, borderPx, sideH);
        update(right, rightLp, Math.max(l, r - borderPx), sideTop, borderPx, sideH);

        label.setSizeText(Math.max(0, r - l) + " × " + Math.max(0, b - t));
        placeLabelOutside(l, t, r, b, w);

        top.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        left.setVisibility(View.VISIBLE);
        right.setVisibility(View.VISIBLE);
    }

    private void placeLabelOutside(int l, int t, int r, int b, int frameWidth) {
        Rect display;
        try { display = new Rect(wm.getCurrentWindowMetrics().getBounds()); }
        catch (Throwable ignored) { display = new Rect(0, 0,
                context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels); }

        int minLabelWidth = Math.round(72f * context.getResources().getDisplayMetrics().density);
        int labelWidth = Math.min(labelMaxWidthPx, Math.max(minLabelWidth, Math.min(frameWidth, labelMaxWidthPx)));
        labelWidth = Math.min(labelWidth, Math.max(1, display.width()));
        int x = clamp(l, display.left, Math.max(display.left, display.right - labelWidth));
        int aboveY = t - labelGapPx - labelHeightPx;
        int belowY = b + labelGapPx;

        if (aboveY >= display.top) {
            update(label, labelLp, x, aboveY, labelWidth, labelHeightPx);
            label.setVisibility(View.VISIBLE);
        } else if (belowY + labelHeightPx <= display.bottom) {
            update(label, labelLp, x, belowY, labelWidth, labelHeightPx);
            label.setVisibility(View.VISIBLE);
        } else {
            // Never place the label inside the selection: hiding it is safer than contaminating
            // screenshots when the rectangle touches both display edges.
            label.setVisibility(View.INVISIBLE);
        }
    }

    private void update(View v, WindowManager.LayoutParams lp, int x, int y, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (lp.x == x && lp.y == y && lp.width == w && lp.height == h) return;
        lp.x = x;
        lp.y = y;
        lp.width = w;
        lp.height = h;
        try { wm.updateViewLayout(v, lp); } catch (Throwable ignored) {}
    }

    private WindowManager.LayoutParams lp(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = -10000;
        p.y = -10000;
        return p;
    }

    private void remove(View v) {
        try { wm.removeView(v); } catch (Throwable ignored) {}
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class EdgeView extends View {
        private final Paint paint = new Paint();
        private boolean confirmed;
        private boolean paintInitialized;

        EdgeView(Context c, boolean ignored) {
            super(c);
            // android.graphics.Paint defaults to opaque BLACK. Always configure the first frame;
            // otherwise confirmed=false would incorrectly look "already initialized" and the
            // region frame would stay black until a later state transition.
            setConfirmed(false);
        }

        void setConfirmed(boolean value) {
            if (paintInitialized && confirmed == value) return;
            confirmed = value;
            SelectionVisuals.configureEdgePaints(paint, null, confirmed);
            paintInitialized = true;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            SelectionVisuals.drawEdge(c, getWidth(), getHeight(), paint);
        }
    }

    private static final class LabelView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String size = "";

        LabelView(Context c) {
            super(c);
            text.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
            text.setTextAlign(Paint.Align.CENTER);
            bg.setColor(0xB8000000);
            setAccentColor(SelectionVisuals.FV_ACTIVE_COLOR);
            setBackgroundColor(Color.TRANSPARENT);
        }

        void setAccentColor(int color) {
            text.setColor(color);
            invalidate();
        }

        void setSizeText(String value) {
            if (value == null) value = "";
            if (value.equals(size)) return;
            size = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (size.isEmpty()) return;
            float radius = 5f * getResources().getDisplayMetrics().density;
            c.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, bg);
            Paint.FontMetrics fm = text.getFontMetrics();
            float y = (getHeight() - fm.bottom - fm.top) / 2f;
            c.drawText(size, getWidth() / 2f, y, text);
        }
    }
}
