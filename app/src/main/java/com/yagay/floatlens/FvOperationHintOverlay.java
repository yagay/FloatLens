package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * FV's independent 24dp operation-state hint (FooViewService.y/z, D4/s3 and m2/g.E).
 *
 * This is deliberately separate from the 15dp red/yellow circle_focus probe. The probe is the
 * exact selection point; this window only tells the user what the current target will do on release:
 * extract text, capture a View/image, or capture a rectangular/screen region.
 */
public final class FvOperationHintOverlay {
    public enum Mode { TEXT, IMAGE, SCREENSHOT }

    private static final float SIZE_DP = 24f;

    private final Context context;
    private final WindowManager wm;
    private final int sizePx;
    private final HintView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;
    private Mode mode = Mode.SCREENSHOT;

    public FvOperationHintOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        sizePx = Math.max(1, Math.round(SIZE_DP * density));
        view = new HintView(context);
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
            DiagnosticLog.i(context, "FV_OP_HINT", "ATTACH size=" + sizePx);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_OP_HINT", "attach failed=" + t);
        }
    }

    /**
     * Mirrors FV D4 placement. The side flag is captured at gesture start and must not flip merely
     * because the dragged icon crosses the centre of the screen.
     */
    public void show(Mode next, RectF iconBounds, boolean gestureLeftSide) {
        if (iconBounds == null) return;
        if (!attached) attachHidden();
        if (next == null) next = Mode.SCREENSHOT;
        if (mode != next) {
            mode = next;
            view.setMode(next);
        }

        lp.x = Math.round(gestureLeftSide
                ? iconBounds.left + iconBounds.width()
                : iconBounds.left - sizePx);
        lp.y = Math.round(iconBounds.top - sizePx);

        if (attached) {
            try {
                wm.updateViewLayout(view, lp);
                if (!visible) {
                    visible = true;
                    view.setVisibility(View.VISIBLE);
                }
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_OP_HINT", "move failed=" + t);
            }
        }
        DiagnosticLog.i(context, "FV_OP_HINT", "mode=" + mode
                + " pos=" + lp.x + "," + lp.y
                + " icon=" + Math.round(iconBounds.left) + "," + Math.round(iconBounds.top)
                + "-" + Math.round(iconBounds.right) + "," + Math.round(iconBounds.bottom)
                + " side=" + (gestureLeftSide ? "L" : "R"));
    }

    public void hide() {
        if (!attached) return;
        visible = false;
        view.setVisibility(View.INVISIBLE);
        lp.x = -sizePx;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
    }

    public void close() {
        visible = false;
        if (attached) {
            try { wm.removeView(view); } catch (Throwable ignored) {}
        }
        attached = false;
    }

    private static final class HintView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Mode mode = Mode.SCREENSHOT;

        HintView(Context c) {
            super(c);
            bg.setStyle(Paint.Style.FILL);
            bg.setColor(0xDD303136);
            fg.setStyle(Paint.Style.STROKE);
            fg.setStrokeCap(Paint.Cap.ROUND);
            fg.setStrokeJoin(Paint.Join.ROUND);
            fg.setStrokeWidth(Math.max(1.5f, 1.7f * getResources().getDisplayMetrics().density));
            fg.setColor(Color.WHITE);
            setBackgroundColor(Color.TRANSPARENT);
        }

        void setMode(Mode value) {
            if (value == null) value = Mode.SCREENSHOT;
            if (mode == value) return;
            mode = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            c.drawCircle(cx, cy, Math.min(w, h) * .46f, bg);

            switch (mode) {
                case TEXT -> drawTextIcon(c, w, h);
                case IMAGE -> drawImageIcon(c, w, h);
                case SCREENSHOT -> drawScreenshotIcon(c, w, h);
            }
        }

        private void drawTextIcon(Canvas c, float w, float h) {
            float l = w * .27f, r = w * .73f;
            c.drawLine(l, h * .34f, r, h * .34f, fg);
            c.drawLine(l, h * .50f, r, h * .50f, fg);
            c.drawLine(l, h * .66f, w * .61f, h * .66f, fg);
        }

        private void drawImageIcon(Canvas c, float w, float h) {
            float l = w * .25f, t = h * .27f, r = w * .75f, b = h * .73f;
            c.drawRect(l, t, r, b, fg);
            c.drawCircle(w * .61f, h * .39f, w * .055f, fg);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(l + w * .04f, b - h * .06f);
            p.lineTo(w * .43f, h * .51f);
            p.lineTo(w * .54f, h * .61f);
            p.lineTo(w * .62f, h * .53f);
            p.lineTo(r - w * .03f, b - h * .06f);
            c.drawPath(p, fg);
        }

        private void drawScreenshotIcon(Canvas c, float w, float h) {
            float l = w * .25f, t = h * .25f, r = w * .75f, b = h * .75f;
            float arm = w * .16f;
            c.drawLine(l, t, l + arm, t, fg); c.drawLine(l, t, l, t + arm, fg);
            c.drawLine(r, t, r - arm, t, fg); c.drawLine(r, t, r, t + arm, fg);
            c.drawLine(l, b, l + arm, b, fg); c.drawLine(l, b, l, b - arm, fg);
            c.drawLine(r, b, r - arm, b, fg); c.drawLine(r, b, r, b - arm, fg);
        }
    }
}
