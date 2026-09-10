package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.Collections;

/**
 * Non-touchable FV-style selection display layer.
 *
 * TEXT and image/icon NON_TEXT are normal candidates. A near-fullscreen ROOT is only the final
 * fallback. This overlay never owns the pointer stream; the original FloatIconView remains the
 * touch owner even after it expands to MATCH_PARENT.
 */
public final class ViewHoverOverlay {
    private static final long TREE_REFRESH_MS = 120L;

    private final Context context;
    private final WindowManager wm;
    private final LensAccessibilityService accessibility;
    private final ScreenSelectionModel model = new ScreenSelectionModel();
    private HoverView view;
    private ScreenCandidate current;
    private boolean confirmed;
    private long lastScanAt;
    private long lastTreeScanAt;
    private float lastX = Float.NaN, lastY = Float.NaN;

    public ViewHoverOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        accessibility = LensAccessibilityService.get();
    }

    public boolean available() { return accessibility != null; }

    /** Compatibility no-op: visual screenshot candidates are no longer used for selection. */
    public void setScreenSnapshot(Bitmap bitmap, Rect displayBounds) {}

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
        try {
            wm.addView(view, lp);
            refreshAccessibilityTree(true);
        } catch (Throwable t) {
            view = null;
            DiagnosticLog.i(context, "VIEW_HOVER", "add failed=" + t);
        }
    }

    public void update(float selectionX, float selectionY) {
        if (accessibility == null) return;
        if (view == null) begin();
        if (view == null) return;

        long now = SystemClock.uptimeMillis();
        float dx = Float.isNaN(lastX) ? 999f : selectionX - lastX;
        float dy = Float.isNaN(lastY) ? 999f : selectionY - lastY;
        if (now - lastScanAt < 20L && dx * dx + dy * dy < 9f) return;
        lastScanAt = now;
        lastX = selectionX;
        lastY = selectionY;

        refreshAccessibilityTree(false);
        ScreenCandidate next = model.selectAt(selectionX, selectionY);
        if (!sameCandidate(current, next)) {
            current = next;
            confirmed = false;
            view.setCandidate(next);
            view.setConfirmed(false);
            if (next != null) {
                DiagnosticLog.i(context, "VIEW_HOVER", "source=" + next.source()
                        + " type=" + next.type() + " screenBounds=" + next.bounds()
                        + " depth=" + next.depth() + " textLen=" + next.text().length()
                        + " class=" + next.className() + " id=" + next.viewId());
            } else {
                DiagnosticLog.i(context, "VIEW_HOVER", "candidate=null selection="
                        + Math.round(selectionX) + "," + Math.round(selectionY));
            }
        }
    }

    private void refreshAccessibilityTree(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastTreeScanAt < TREE_REFRESH_MS
                && !model.accessibilityCandidates().isEmpty()) return;
        lastTreeScanAt = now;
        try {
            model.setAccessibility(AccessibilityCandidateCollector.collect(accessibility));
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_TREE", "refresh failed=" + t);
            model.setAccessibility(Collections.emptyList());
        }
    }

    public void setConfirmed(boolean value) {
        confirmed = value && current != null;
        if (view != null) view.setConfirmed(confirmed);
    }

    public boolean isConfirmed() { return confirmed; }

    /** Compatibility path for the old dwell-confirmed flow. */
    public boolean finish(boolean extract) {
        ScreenCandidate picked = current;
        boolean wasConfirmed = confirmed;
        close();
        if (!extract || !wasConfirmed) return false;
        return extractPicked(picked, "VIEW_EXTRACT");
    }

    /** FV direct-drag path: ACTION_UP immediately completes the candidate under the current pointer. */
    public boolean finishDirect() {
        ScreenCandidate picked = current;
        close();
        return extractPicked(picked, "FV_DIRECT_EXTRACT");
    }

    private boolean extractPicked(ScreenCandidate picked, String logTag) {
        if (picked == null) return false;
        Rect b = picked.bounds();
        if (b.isEmpty()) return false;
        if (picked.type() != ScreenCandidate.Type.TEXT
                && picked.type() != ScreenCandidate.Type.NON_TEXT
                && picked.type() != ScreenCandidate.Type.ROOT) return false;

        ScreenshotController.captureBoundsForViewCandidate(
                context, b, picked.toViewNodeCandidate(), picked.hasText() ? picked.text() : "");
        DiagnosticLog.i(context, logTag, "capture type=" + picked.type()
                + " bounds=" + b + " textLen=" + picked.text().length()
                + " class=" + picked.className() + " id=" + picked.viewId());
        return true;
    }

    public void cancel() { close(); }
    public boolean hasCandidate() { return current != null && !current.bounds().isEmpty(); }
    public ScreenCandidate currentCandidate() { return current; }

    private void close() {
        if (view != null) try { wm.removeView(view); } catch (Throwable ignored) {}
        view = null;
        current = null;
        confirmed = false;
        model.setAccessibility(Collections.emptyList());
        model.setVisual(Collections.emptyList());
        lastScanAt = lastTreeScanAt = 0L;
        lastX = lastY = Float.NaN;
    }

    private boolean sameCandidate(ScreenCandidate a, ScreenCandidate b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.stableKey().equals(b.stableKey());
    }

    private static final class HoverView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] overlayLocation = new int[2];
        private ScreenCandidate candidate;
        private boolean confirmed;
        private int lastLoggedOriginX = Integer.MIN_VALUE;
        private int lastLoggedOriginY = Integer.MIN_VALUE;

        HoverView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            fill.setStyle(Paint.Style.FILL);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(0xFFFFFFFF);
            label.setColor(Color.WHITE);
            label.setTextSize(dp(14));
            label.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            updatePaints();
        }

        void setCandidate(ScreenCandidate c) { candidate = c; invalidate(); }
        void setConfirmed(boolean value) { confirmed = value; updatePaints(); invalidate(); }

        private void updatePaints() {
            fill.setColor(confirmed ? 0x552196F3 : 0x332196F3);
            border.setStrokeWidth(dp(confirmed ? 4 : 2));
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (candidate == null) return;

            getLocationOnScreen(overlayLocation);
            Rect r = candidate.bounds();
            r.offset(-overlayLocation[0], -overlayLocation[1]);

            if (overlayLocation[0] != lastLoggedOriginX || overlayLocation[1] != lastLoggedOriginY) {
                lastLoggedOriginX = overlayLocation[0];
                lastLoggedOriginY = overlayLocation[1];
                DiagnosticLog.i(getContext(), "VIEW_DRAW", "overlayOrigin="
                        + overlayLocation[0] + "," + overlayLocation[1]
                        + " size=" + getWidth() + "x" + getHeight());
            }

            Rect localFrame = new Rect(0, 0, getWidth(), getHeight());
            if (!Rect.intersects(localFrame, r)) return;

            c.drawRect(r, fill);
            c.drawRect(r, border);
            String base = candidate.type() == ScreenCandidate.Type.ROOT ? "整屏 View" : candidate.label();
            String text = confirmed ? "已锁定 · " + base : base;
            float x = Math.max(dp(8), Math.min(r.left, getWidth() - dp(180)));
            float y = r.top > dp(28) ? r.top - dp(8)
                    : Math.min(getHeight() - dp(8), r.bottom + dp(20));
            if (text.length() > 90) text = text.substring(0, 90) + "…";
            c.drawText(text, x, y, label);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
