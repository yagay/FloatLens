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
 * FV's real selection probe (FooViewService M/N/O/Q).
 *
 * fooView 1.6.4 creates a 15dp FrameLayout/ImageView and uses the circle_focus drawable. e4(Point)
 * centres that Window on the transformed selection Point; the same Point is passed to the selection
 * layer and View hit-test path. FloatLens mirrors that invariant: visible plus centre == hit point.
 */
public final class FvProbePointOverlay {
    private static final float FV_PROBE_SIZE_DP = 15f;
    private static final int FV_FOCUS_COLOR = 0xFFC2185B;

    private final Context context;
    private final WindowManager wm;
    private final int sizePx;
    private final ProbeView view;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean visible;

    public FvProbePointOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        sizePx = Math.max(1, Math.round(FV_PROBE_SIZE_DP * density));
        view = new ProbeView(context);
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
            DiagnosticLog.i(context, "FV_PROBE_VIEW", "ATTACH size=" + sizePx);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_PROBE_VIEW", "attach failed=" + t);
        }
    }

    /** Mirrors FooViewService.e4(Point): O.x=p.x-Q/2, O.y=p.y-Q/2. */
    public PointF showAt(float screenX, float screenY) {
        if (!attached) attachHidden();
        lp.x = Math.round(screenX - sizePx / 2f);
        lp.y = Math.round(screenY - sizePx / 2f);
        if (attached) {
            try {
                wm.updateViewLayout(view, lp);
                if (!visible) {
                    visible = true;
                    view.setVisibility(View.VISIBLE);
                }
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_PROBE_VIEW", "move failed=" + t);
            }
        }
        float cx = lp.x + sizePx / 2f;
        float cy = lp.y + sizePx / 2f;
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "MOVE centre=" + Math.round(cx) + ","
                + Math.round(cy) + " window=" + lp.x + "," + lp.y);
        return new PointF(cx, cy);
    }

    /** Mirrors FV F3(): hide the ImageView and move its tiny Window off-screen. */
    public void hide() {
        if (!attached) return;
        visible = false;
        view.setVisibility(View.INVISIBLE);
        lp.x = -sizePx;
        try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "HIDE");
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

        ProbeView(Context c) {
            super(c);
            plus.setColor(FV_FOCUS_COLOR);
            plus.setStyle(Paint.Style.STROKE);
            plus.setStrokeCap(Paint.Cap.SQUARE);
            plus.setStrokeWidth(Math.max(1f, 1.8f * getResources().getDisplayMetrics().density));
            // FV circle_focus PNG has a subtle dark shadow around the magenta plus.
            plus.setShadowLayer(Math.max(1f, 1.25f * getResources().getDisplayMetrics().density), 0f,
                    Math.max(.5f, .5f * getResources().getDisplayMetrics().density), 0x66000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setBackgroundColor(Color.TRANSPARENT);
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
