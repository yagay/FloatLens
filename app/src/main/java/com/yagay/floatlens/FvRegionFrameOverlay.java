package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Lightweight rectangular region indicator for video-friendly direct selection.
 * Only four thin non-touchable surfaces are composed; no fullscreen translucent surface exists.
 */
final class FvRegionFrameOverlay {
    private final Context context;
    private final WindowManager wm;
    private final int borderPx;
    private final int innerBorderPx;
    private final int labelHeightPx;
    private final LabelView top;
    private final View bottom;
    private final View left;
    private final View right;
    private final WindowManager.LayoutParams topLp;
    private final WindowManager.LayoutParams bottomLp;
    private final WindowManager.LayoutParams leftLp;
    private final WindowManager.LayoutParams rightLp;
    private final Rect pending = new Rect();
    private boolean attached;
    private boolean framePosted;
    private final Runnable applyRunnable = this::applyPending;

    FvRegionFrameOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float d = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        borderPx = SelectionVisuals.edgeThicknessPx(context);
        innerBorderPx = SelectionVisuals.edgeInnerThicknessPx(context);
        labelHeightPx = Math.max(borderPx + 1, Math.round(27f * d));
        top = new LabelView(context, borderPx, innerBorderPx);
        bottom = new EdgeView(context, false, innerBorderPx);
        left = new EdgeView(context, true, innerBorderPx);
        right = new EdgeView(context, true, innerBorderPx);
        topLp = lp(1, labelHeightPx);
        bottomLp = lp(1, borderPx);
        leftLp = lp(borderPx, 1);
        rightLp = lp(borderPx, 1);
    }

    void show(Rect screenRect) {
        if (screenRect == null || screenRect.width() < 2 || screenRect.height() < 2) return;
        pending.set(screenRect);
        ensureAttached();
        if (!attached || framePosted) return;
        framePosted = true;
        top.postOnAnimation(applyRunnable);
    }

    void close() {
        framePosted = false;
        try { top.removeCallbacks(applyRunnable); } catch (Throwable ignored) {}
        if (!attached) return;
        remove(top); remove(bottom); remove(left); remove(right);
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
            wm.addView(top, topLp);
            wm.addView(bottom, bottomLp);
            wm.addView(left, leftLp);
            wm.addView(right, rightLp);
            attached = true;
            DiagnosticLog.i(context, "FV_REGION_FRAME", "ATTACH high-contrast thin-surfaces");
        } catch (Throwable t) {
            remove(top); remove(bottom); remove(left); remove(right);
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

        update(top, topLp, l, t, w, Math.min(labelHeightPx, h));
        update(bottom, bottomLp, l, Math.max(t, b - borderPx), w, borderPx);
        int sideTop = Math.min(b - borderPx, t + Math.min(labelHeightPx, h));
        int sideH = Math.max(borderPx, b - sideTop - borderPx);
        update(left, leftLp, l, sideTop, borderPx, sideH);
        update(right, rightLp, Math.max(l, r - borderPx), sideTop, borderPx, sideH);
        top.setSizeText((r - l) + " × " + (b - t));
        top.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        left.setVisibility(View.VISIBLE);
        right.setVisibility(View.VISIBLE);
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

    private static final class EdgeView extends View {
        private final Paint outer = new Paint();
        private final Paint inner = new Paint();
        private final boolean vertical;
        private final int innerPx;

        EdgeView(Context c, boolean vertical, int innerPx) {
            super(c);
            this.vertical = vertical;
            this.innerPx = innerPx;
            SelectionVisuals.configureEdgePaints(outer, inner);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            SelectionVisuals.drawEdge(c, getWidth(), getHeight(), vertical, innerPx, outer, inner);
        }
    }

    private static final class LabelView extends View {
        private final Paint edgeOuter = new Paint();
        private final Paint edgeInner = new Paint();
        private final Paint textOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int borderPx;
        private final int innerBorderPx;
        private String size = "";

        LabelView(Context c, int borderPx, int innerBorderPx) {
            super(c);
            this.borderPx = borderPx;
            this.innerBorderPx = innerBorderPx;
            SelectionVisuals.configureEdgePaints(edgeOuter, edgeInner);
            SelectionVisuals.configureTextPaints(c, textOutline, textFill, 14f);
            setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        void setSizeText(String value) {
            if (value == null) value = "";
            if (value.equals(size)) return;
            size = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            SelectionVisuals.drawHorizontalEdge(c, getWidth(), Math.min(borderPx, getHeight()),
                    innerBorderPx, edgeOuter, edgeInner);
            if (!size.isEmpty() && getHeight() > borderPx) {
                float d = getResources().getDisplayMetrics().density;
                SelectionVisuals.drawText(c, size, Math.max(4f, 8f * d),
                        getHeight() - 6f * d, textOutline, textFill);
            }
        }
    }
}
