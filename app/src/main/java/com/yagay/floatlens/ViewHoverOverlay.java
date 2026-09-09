package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.List;

/**
 * Non-touchable FV-style hover layer controlled by the floating icon drag path.
 * It never owns the gesture: FloatIconView keeps receiving all MotionEvents.
 */
public final class ViewHoverOverlay {
    private final Context context;
    private final WindowManager wm;
    private final LensAccessibilityService accessibility;
    private HoverView view;
    private ViewNodeCandidate current;
    private long lastScanAt;
    private float lastX = Float.NaN, lastY = Float.NaN;

    public ViewHoverOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        accessibility = LensAccessibilityService.get();
    }

    public boolean available() { return accessibility != null; }

    public void begin() {
        if (accessibility == null || view != null) return;
        view = new HoverView(context);
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
        try { wm.addView(view, lp); }
        catch (Throwable t) { view = null; DiagnosticLog.i(context,"VIEW_HOVER","add failed="+t); }
    }

    public void update(float screenX, float screenY) {
        if (accessibility == null) return;
        if (view == null) begin();
        if (view == null) return;
        long now = SystemClock.uptimeMillis();
        float dx = Float.isNaN(lastX) ? 999f : screenX - lastX;
        float dy = Float.isNaN(lastY) ? 999f : screenY - lastY;
        if (now - lastScanAt < 40 && dx*dx + dy*dy < 64f) return;
        lastScanAt = now; lastX = screenX; lastY = screenY;
        ViewNodeCandidate next = accessibility.findViewAt(screenX, screenY);
        if (!sameCandidate(current, next)) {
            current = next;
            view.setCandidate(next);
            if (next != null) DiagnosticLog.i(context,"VIEW_HOVER","bounds="+next.bounds()+" textLen="+next.text().length()+" class="+next.className()+" id="+next.viewId());
        }
    }

    /**
     * Finishes hover selection. When extract=true and a useful candidate exists, this consumes
     * the drag release and performs View text extraction / View-bounds OCR fallback.
     */
    public boolean finish(boolean extract) {
        ViewNodeCandidate picked = current;
        close();
        if (!extract || picked == null) return false;
        Rect b = picked.bounds();
        if (b.isEmpty()) return false;
        if (picked.hasText()) {
            FloatService f = FloatService.get();
            if (f != null) f.onOcrResults(1);
            ResultOverlay.show(context, picked.text(), List.of(picked.text()), null);
            DiagnosticLog.i(context,"VIEW_EXTRACT","direct text bounds="+b+" len="+picked.text().length());
            return true;
        }
        ScreenshotController.captureBoundsForOcr(context, b);
        DiagnosticLog.i(context,"VIEW_EXTRACT","bounds OCR="+b+" class="+picked.className());
        return true;
    }

    public void cancel() { close(); }
    public boolean hasCandidate() { return current != null && !current.bounds().isEmpty(); }

    private void close() {
        if (view != null) try { wm.removeView(view); } catch (Throwable ignored) {}
        view = null; current = null; lastScanAt = 0; lastX = lastY = Float.NaN;
    }

    private boolean sameCandidate(ViewNodeCandidate a, ViewNodeCandidate b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.bounds().equals(b.bounds()) && a.text().equals(b.text()) && a.className().equals(b.className()) && a.viewId().equals(b.viewId());
    }

    private static final class HoverView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ViewNodeCandidate candidate;

        HoverView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            fill.setStyle(Paint.Style.FILL); fill.setColor(0x332196F3);
            border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(dp(2)); border.setColor(0xFFFFFFFF);
            label.setColor(Color.WHITE); label.setTextSize(dp(14)); label.setShadowLayer(dp(3),0,dp(1),Color.BLACK);
        }

        void setCandidate(ViewNodeCandidate c) { candidate = c; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (candidate == null) return;
            Rect r = candidate.bounds();
            c.drawRect(r, fill); c.drawRect(r, border);
            String text = candidate.label();
            float x = Math.max(dp(8), Math.min(r.left, getWidth() - dp(180)));
            float y = r.top > dp(28) ? r.top - dp(8) : Math.min(getHeight()-dp(8), r.bottom + dp(20));
            if (text.length() > 90) text = text.substring(0,90) + "…";
            c.drawText(text, x, y, label);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
