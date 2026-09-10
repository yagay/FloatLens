package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * FV-style independent 15dp probe-point window.
 *
 * fooView keeps a tiny helper window attached and only toggles the child visibility. The window is
 * centred on the same transformed ProbePoint that is used for View hit testing.
 */
public final class FvProbePointOverlay {
    private static final float FV_PROBE_SIZE_DP = 15f;

    private final Context context;
    private final WindowManager wm;
    private final int sizePx;
    private final FrameLayout root;
    private final ProbeDotView dot;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;

    public FvProbePointOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        sizePx = Math.max(1, Math.round(FV_PROBE_SIZE_DP
                * context.getResources().getDisplayMetrics().density));

        root = new FrameLayout(context);
        dot = new ProbeDotView(context);
        root.addView(dot, new FrameLayout.LayoutParams(sizePx, sizePx));
        dot.setVisibility(View.INVISIBLE);

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
            wm.addView(root, lp);
            attached = true;
            DiagnosticLog.i(context, "FV_PROBE_DOT", "ATTACH size=" + sizePx);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_PROBE_DOT", "attach failed=" + t);
        }
    }

    /** Position the 15dp window so its centre is exactly the selection ProbePoint. */
    public void showAt(float screenX, float screenY) {
        if (!attached) attachHidden();
        if (!attached) return;
        lp.x = Math.round(screenX - sizePx / 2f);
        lp.y = Math.round(screenY - sizePx / 2f);
        try {
            wm.updateViewLayout(root, lp);
            if (!visible) {
                dot.setVisibility(View.VISIBLE);
                visible = true;
                DiagnosticLog.i(context, "FV_PROBE_DOT", "SHOW centre="
                        + Math.round(screenX) + "," + Math.round(screenY)
                        + " window=" + lp.x + "," + lp.y + " size=" + sizePx);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_PROBE_DOT", "move failed=" + t);
        }
    }

    public void hide() {
        if (!attached) return;
        if (!visible && lp.x == -sizePx) return;
        visible = false;
        dot.setVisibility(View.INVISIBLE);
        lp.x = -sizePx;
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
        DiagnosticLog.i(context, "FV_PROBE_DOT", "HIDE");
    }

    public void close() {
        visible = false;
        if (attached) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
        }
        attached = false;
    }

    private static final class ProbeDotView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

        ProbeDotView(Context c) {
            super(c);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xDD1976D2);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, 1.5f * getResources().getDisplayMetrics().density));
            border.setColor(Color.WHITE);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - border.getStrokeWidth());
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, border);
        }
    }
}
