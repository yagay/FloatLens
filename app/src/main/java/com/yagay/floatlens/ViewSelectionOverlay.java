package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** Explicit full-screen View picker used by ActionId.OCR. */
public final class ViewSelectionOverlay {
    public static void show(Context c) {
        Context app = c.getApplicationContext();
        LensAccessibilityService accessibility = LensAccessibilityService.get();
        if (accessibility == null) {
            Toast.makeText(app, "需要开启 FloatLens 无障碍服务，已改用 OCR 圈选", Toast.LENGTH_SHORT).show();
            ScreenshotController.captureForOcr(app);
            return;
        }

        FlOverlayWindowHost host = new FlOverlayWindowHost(app);
        PickView view = new PickView(app, accessibility, host);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (!host.addApplication(view, lp, "explicit_view_picker")) {
            Toast.makeText(app, "View 选择层启动失败，改用 OCR", Toast.LENGTH_SHORT).show();
            ScreenshotController.captureForOcr(app);
        }
    }

    private static final class PickView extends View {
        private final LensAccessibilityService accessibility;
        private final FlOverlayWindowHost host;
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ViewNodeCandidate current;
        private long lastScan;
        private boolean closed;

        PickView(Context c, LensAccessibilityService accessibility, FlOverlayWindowHost host) {
            super(c);
            this.accessibility = accessibility;
            this.host = host;
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(2));
            border.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x332196F3);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(15));
            textPaint.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            setBackgroundColor(0x22000000);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (current != null) {
                Rect r = current.bounds();
                canvas.drawRect(r, fill);
                canvas.drawRect(r, border);
                float y = Math.max(dp(24), r.top - dp(8));
                canvas.drawText(current.label(), Math.max(dp(8), r.left), y, textPaint);
            } else {
                canvas.drawText("移动手指选择 View · 松手提取文字", dp(18), dp(42), textPaint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (closed) return true;
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                long now = SystemClock.uptimeMillis();
                if (action == MotionEvent.ACTION_DOWN || now - lastScan >= 40) {
                    lastScan = now;
                    current = accessibility.findViewAt(e.getRawX(), e.getRawY());
                    if (current != null) {
                        DiagnosticLog.i(getContext(), "VIEW_PICK", "bounds=" + current.bounds()
                                + " textLen=" + current.text().length()
                                + " class=" + current.className());
                    }
                    invalidate();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                finishSelection();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                close();
                return true;
            }
            return true;
        }

        private void finishSelection() {
            ViewNodeCandidate picked = current;
            close();
            if (picked == null) {
                ScreenshotController.captureForOcr(getContext());
                return;
            }
            if (picked.hasText()) {
                FloatService service = FloatService.get();
                if (service != null) service.onOcrResults(1);
                ResultSurfaceRouter.showOcr(getContext(), picked.text(),
                        java.util.List.of(picked.text()), null, null);
                return;
            }
            Rect bounds = picked.bounds();
            if (!bounds.isEmpty()) ScreenshotController.captureBoundsForOcr(getContext(), bounds);
            else ScreenshotController.captureForOcr(getContext());
        }

        private void close() {
            if (closed) return;
            closed = true;
            host.remove(this, "explicit_view_picker");
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }

    private ViewSelectionOverlay() {}
}
