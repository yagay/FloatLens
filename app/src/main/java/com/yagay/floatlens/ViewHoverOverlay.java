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
import java.util.List;

/**
 * Non-touchable FV-style selection layer.
 *
 * A full Accessibility Text/Edit/NonText candidate tree is primary. Screenshot visual rectangles are
 * a fallback/refinement only when Accessibility cannot expose a concrete target (or only exposes a
 * coarse cell such as icon+label). All candidates remain in screen coordinates before drawing.
 */
public final class ViewHoverOverlay {
    private static final long TREE_REFRESH_MS = 140L;
    private static final long VISUAL_REFRESH_MS = 90L;

    private final Context context;
    private final WindowManager wm;
    private final LensAccessibilityService accessibility;
    private final ScreenSelectionModel model = new ScreenSelectionModel();
    private HoverView view;
    private ScreenCandidate current;
    private Bitmap screenSnapshot;
    private Rect snapshotScreenBounds;
    private long lastScanAt;
    private long lastTreeScanAt;
    private long lastVisualScanAt;
    private float lastX = Float.NaN, lastY = Float.NaN;

    public ViewHoverOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        accessibility = LensAccessibilityService.get();
    }

    public boolean available() { return accessibility != null; }

    public void setScreenSnapshot(Bitmap bitmap, Rect displayBounds) {
        screenSnapshot = bitmap;
        snapshotScreenBounds = displayBounds == null ? null : new Rect(displayBounds);
        if (!Float.isNaN(lastX) && !Float.isNaN(lastY)) update(lastX, lastY, true);
    }

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

    public void update(float selectionX, float selectionY) { update(selectionX, selectionY, false); }

    private void update(float selectionX, float selectionY, boolean forceVisual) {
        if (accessibility == null) return;
        if (view == null) begin();
        if (view == null) return;

        long now = SystemClock.uptimeMillis();
        float dx = Float.isNaN(lastX) ? 999f : selectionX - lastX;
        float dy = Float.isNaN(lastY) ? 999f : selectionY - lastY;
        if (!forceVisual && now - lastScanAt < 24L && dx * dx + dy * dy < 16f) return;
        lastScanAt = now;
        lastX = selectionX;
        lastY = selectionY;

        refreshAccessibilityTree(false);
        ScreenCandidate accessSelected = model.selectAccessibilityAt(selectionX, selectionY);

        boolean allowVisual = shouldUseVisual(accessSelected);
        if (allowVisual && screenSnapshot != null && !screenSnapshot.isRecycled()
                && snapshotScreenBounds != null && !snapshotScreenBounds.isEmpty()
                && (forceVisual || now - lastVisualScanAt >= VISUAL_REFRESH_MS)) {
            lastVisualScanAt = now;
            Rect hint = accessSelected == null ? null : accessSelected.bounds();
            try {
                model.setVisual(VisualCandidateDetector.detect(context, screenSnapshot,
                        snapshotScreenBounds, selectionX, selectionY, hint));
            } catch (Throwable t) {
                model.setVisual(Collections.emptyList());
                DiagnosticLog.i(context, "VIEW_VISUAL", "fallback detect failed=" + t);
            }
        } else if (!allowVisual) {
            // Never let a stale screenshot rectangle replace a newly available Accessibility node.
            model.setVisual(Collections.emptyList());
        }

        ScreenCandidate next = model.selectAt(selectionX, selectionY);
        if (!sameCandidate(current, next)) {
            current = next;
            view.setCandidate(next);
            if (next != null) {
                DiagnosticLog.i(context, "VIEW_HOVER", "source=" + next.source()
                        + " type=" + next.type() + " bounds=" + next.bounds()
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
        if (!force && now - lastTreeScanAt < TREE_REFRESH_MS && !model.accessibilityCandidates().isEmpty()) return;
        lastTreeScanAt = now;
        try {
            model.setAccessibility(AccessibilityCandidateCollector.collect(accessibility));
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_TREE", "refresh failed=" + t);
        }
    }

    private boolean shouldUseVisual(ScreenCandidate access) {
        if (access == null || access.fullscreenLike() || access.type() == ScreenCandidate.Type.ROOT) return true;
        // A known Accessibility NON_TEXT node already has an exact getBoundsInScreen rectangle.
        if (access.source() == ScreenCandidate.Source.ACCESSIBILITY
                && access.type() == ScreenCandidate.Type.NON_TEXT) return false;
        Rect r = access.bounds();
        float density = context.getResources().getDisplayMetrics().density;
        return r.width() >= 42f * density && r.height() >= 42f * density;
    }

    /** Resolve the currently highlighted unified candidate. */
    public boolean finish(boolean extract) {
        ScreenCandidate picked = current;
        close();
        if (!extract || picked == null) return false;
        Rect b = picked.bounds();
        if (b.isEmpty()) return false;

        if (picked.hasText() && picked.type() == ScreenCandidate.Type.TEXT) {
            FloatService f = FloatService.get();
            if (f != null) f.onOcrResults(1);
            ResultOverlay.show(context, picked.text(), List.of(picked.text()), null);
            DiagnosticLog.i(context, "VIEW_EXTRACT", "direct text source=" + picked.source()
                    + " bounds=" + b + " len=" + picked.text().length());
            return true;
        }

        ScreenshotController.captureBoundsForVisualCandidate(context, b, picked.toViewNodeCandidate());
        DiagnosticLog.i(context, "VIEW_EXTRACT", "visual/nonText source=" + picked.source()
                + " type=" + picked.type() + " bounds=" + b
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
        model.setAccessibility(Collections.emptyList());
        model.setVisual(Collections.emptyList());
        screenSnapshot = null;
        snapshotScreenBounds = null;
        lastScanAt = lastTreeScanAt = lastVisualScanAt = 0L;
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
        private ScreenCandidate candidate;

        HoverView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            fill.setStyle(Paint.Style.FILL); fill.setColor(0x332196F3);
            border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(dp(2)); border.setColor(0xFFFFFFFF);
            label.setColor(Color.WHITE); label.setTextSize(dp(14)); label.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        }

        void setCandidate(ScreenCandidate c) { candidate = c; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (candidate == null) return;
            Rect r = candidate.bounds();
            c.drawRect(r, fill);
            c.drawRect(r, border);
            String text = candidate.label();
            float x = Math.max(dp(8), Math.min(r.left, getWidth() - dp(180)));
            float y = r.top > dp(28) ? r.top - dp(8) : Math.min(getHeight() - dp(8), r.bottom + dp(20));
            if (text.length() > 90) text = text.substring(0, 90) + "…";
            c.drawText(text, x, y, label);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
