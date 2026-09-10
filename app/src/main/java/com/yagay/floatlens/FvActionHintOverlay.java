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
 * FV-style 24dp action helper shown beside the moving floating icon.
 *
 * Runtime captures show a separate helper window beside the icon. On the right edge it is placed
 * one helper-width to the left and one helper-height above the icon; on the left edge it is placed
 * immediately to the icon's right and one helper-height above it.
 */
public final class FvActionHintOverlay {
    private static final float FV_HELPER_SIZE_DP = 24f;

    private final Context context;
    private final WindowManager wm;
    private final int sizePx;
    private final HintView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;

    public FvActionHintOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        sizePx = Math.max(1, Math.round(FV_HELPER_SIZE_DP
                * context.getResources().getDisplayMetrics().density));
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
            DiagnosticLog.i(context, "FV_ACTION_HINT", "ATTACH size=" + sizePx);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_ACTION_HINT", "attach failed=" + t);
        }
    }

    public void showNextTo(View icon) {
        if (icon == null) return;
        if (!attached) attachHidden();
        if (!attached) return;

        int[] loc = new int[2];
        try { icon.getLocationOnScreen(loc); } catch (Throwable t) { return; }
        Rect screen = screenBounds();
        float iconCenter = loc[0] + icon.getWidth() / 2f;
        float screenCenter = screen.isEmpty() ? iconCenter : screen.exactCenterX();
        boolean leftSide = iconCenter < screenCenter;

        lp.x = leftSide ? loc[0] + icon.getWidth() : loc[0] - sizePx;
        lp.y = loc[1] - sizePx;
        try {
            wm.updateViewLayout(view, lp);
            if (!visible) {
                view.setVisibility(View.VISIBLE);
                visible = true;
                DiagnosticLog.i(context, "FV_ACTION_HINT", "SHOW side="
                        + (leftSide ? "L" : "R") + " icon=" + loc[0] + "," + loc[1]
                        + " helper=" + lp.x + "," + lp.y + " size=" + sizePx);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_ACTION_HINT", "move failed=" + t);
        }
    }

    public void hide() {
        if (!attached) return;
        if (!visible && lp.x == -sizePx) return;
        visible = false;
        view.setVisibility(View.INVISIBLE);
        lp.x = -sizePx;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        DiagnosticLog.i(context, "FV_ACTION_HINT", "HIDE");
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

        HintView(Context c) {
            super(c);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xE62B2D31);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, 1.5f * getResources().getDisplayMetrics().density));
            border.setColor(Color.WHITE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeWidth(Math.max(2f, 2f * getResources().getDisplayMetrics().density));
            glyph.setColor(Color.WHITE);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - border.getStrokeWidth());
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, border);
            float d = r * .38f;
            canvas.drawLine(cx - d, cy, cx + d, cy, glyph);
            canvas.drawLine(cx, cy - d, cx, cy + d, glyph);
        }
    }
}
