package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Passive gesture-trail layer. It never owns pointer input. */
final class GestureTrailOverlay {
    private final Context context;
    private final FlOverlayWindowHost windowHost;
    private final TrailView view;
    private boolean attached;

    GestureTrailOverlay(Context c) {
        context = c.getApplicationContext();
        windowHost = new FlOverlayWindowHost(context);
        view = new TrailView(context);
    }

    void begin(float x, float y) {
        ensureAttached();
        view.reload();
        view.begin(x, y);
    }

    void add(float x, float y) {
        if (attached) view.add(x, y);
    }

    void end() {
        if (!attached) return;
        windowHost.remove(view, "gesture_trail");
        attached = false;
    }

    private void ensureAttached() {
        if (attached) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        attached = windowHost.add(view, lp, "gesture_trail");
        if (!attached) {
            DiagnosticLog.i(context, "GESTURE_TRAIL", "overlay add failed");
        } else {
            DiagnosticLog.i(context, "GESTURE_TRAIL", "host="
                    + (windowHost.isAccessibilityHosted(view) ? "accessibility" : "application"));
        }
    }

    private static final class TrailView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private FloatSettings settings;

        TrailView(Context c) {
            super(c);
            reload();
        }

        void reload() {
            settings = new FloatSettings(getContext());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(settings.lineWidthDp() * getResources().getDisplayMetrics().density);
            paint.setAlpha(Math.round(255 * settings.lineAlpha() / 100f));
            paint.setStrokeCap(settings.lineStyle() == 1 ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
            try {
                String[] colors = settings.lineColors().split("[,; ]+");
                int first = Color.parseColor(colors[0]);
                paint.setColor(first);
                if (settings.lineGradient() && colors.length > 1) {
                    int second = Color.parseColor(colors[1]);
                    paint.setShader(new LinearGradient(
                            0, 0,
                            getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels,
                            first, second, Shader.TileMode.CLAMP));
                } else {
                    paint.setShader(null);
                }
            } catch (Throwable t) {
                paint.setShader(null);
                paint.setColor(Color.WHITE);
            }
        }

        void begin(float x, float y) {
            path.reset();
            path.moveTo(x, y);
            invalidate();
        }

        void add(float x, float y) {
            path.lineTo(x, y);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(path, paint);
        }
    }
}
