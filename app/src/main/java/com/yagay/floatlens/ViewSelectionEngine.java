package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * During drag the helper is a PLUS. Once direct View selection starts, it becomes DOT. From that
 * moment the exact geometric centre of the visible DOT is the single source of truth for View hit
 * testing, candidate highlighting, region selection and release. What the user sees is what selects.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;

    private ViewHoverOverlay overlay;
    private FvActionHintOverlay indicatorOverlay;
    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;

    public ViewSelectionEngine(Context c) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        FloatSettings fs = new FloatSettings(context);
        float px = fs.sizeDp() * context.getResources().getDisplayMetrics().density;
        pointTransformer = new SelectionPointTransformer(context, px, px);
    }

    public boolean available() { return accessibility != null; }
    public boolean isActive() { return state == State.DIRECT; }
    public State state() { return state; }

    /** Capture the original icon-local offset and FV side at ACTION_DOWN. */
    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            ensureIndicator();
            pointTransformer.begin(e);
            PointF p = pointTransformer.transform(e);
            selectionX = p.x;
            selectionY = p.y;
            DiagnosticLog.i(context, "FV_SELECT", "DOWN preDirectProbe="
                    + Math.round(selectionX) + "," + Math.round(selectionY)
                    + " side=" + (pointTransformer.gestureLeftSide() ? "L" : "R"));
        } else if (action == MotionEvent.ACTION_MOVE && state == State.DIRECT) {
            updateDirect(e.getRawX(), e.getRawY());
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
        }
    }

    /** Drag phase: keep the FV pre-direct probe current, while visually showing PLUS. */
    public PointF showProbe(float rawX, float rawY) {
        PointF p = pointTransformer.transformRaw(rawX, rawY);
        selectionX = p.x;
        selectionY = p.y;
        if (state == State.IDLE) {
            ensureIndicator();
            showIndicatorForRaw(FvActionHintOverlay.Mode.PLUS, rawX, rawY);
        }
        return p;
    }

    public void hideProbe() {
        if (indicatorOverlay != null) indicatorOverlay.hide();
    }

    /** Called by the observed FV-style q Runnable while the finger is still down. */
    public boolean activateDirect(float rawX, float rawY) {
        if (accessibility == null) return false;
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        overlay = new ViewHoverOverlay(context);
        if (!overlay.available()) {
            overlay = null;
            if (indicatorOverlay != null) indicatorOverlay.hide();
            return false;
        }

        overlay.begin();
        state = State.DIRECT;
        ensureIndicator();

        // Critical invariant: direct-selection coordinate == visible DOT centre.
        PointF dot = showIndicatorForRaw(FvActionHintOverlay.Mode.DOT, rawX, rawY);
        selectionX = dot.x;
        selectionY = dot.y;

        overlay.beginDirect(selectionX, selectionY);
        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_ENTER raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " dotHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " side=" + (pointTransformer.gestureLeftSide() ? "L" : "R"));
        return true;
    }

    public void updateDirect(float rawX, float rawY) {
        if (state != State.DIRECT || overlay == null) return;

        // Every MOVE first places the visible DOT, then uses that exact centre for hit testing.
        PointF dot = showIndicatorForRaw(FvActionHintOverlay.Mode.DOT, rawX, rawY);
        selectionX = dot.x;
        selectionY = dot.y;
        overlay.updateDirect(selectionX, selectionY);
    }

    private PointF showIndicatorForRaw(FvActionHintOverlay.Mode mode, float rawX, float rawY) {
        RectF icon = pointTransformer.iconBoundsForRaw(rawX, rawY);
        boolean left = pointTransformer.gestureLeftSide();

        if (indicatorOverlay == null) {
            // Geometry-only fallback should normally never be used, because ensureIndicator() is
            // called before both drag and direct-selection paths.
            float helper = 24f * context.getResources().getDisplayMetrics().density;
            float x = left ? icon.left + icon.width() : icon.left - helper;
            float y = icon.top - helper;
            return new PointF(x + helper / 2f, y + helper / 2f);
        }

        if (mode == FvActionHintOverlay.Mode.DOT) {
            return indicatorOverlay.showDotNextTo(
                    icon.left, icon.top, icon.width(), icon.height(), left);
        }
        return indicatorOverlay.showPlusNextTo(
                icon.left, icon.top, icon.width(), icon.height(), left);
    }

    /** Same-touch ACTION_UP: a dragged region wins; otherwise complete the current View candidate. */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);
        boolean region = overlay != null && overlay.isRegionMode();
        boolean hadTarget = overlay != null && overlay.hasCandidate();
        boolean result = overlay != null && overlay.finishDirect();
        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_UP region=" + region
                + " target=" + hadTarget + " result=" + result + " dotHit="
                + Math.round(selectionX) + "," + Math.round(selectionY));
        overlay = null;
        state = State.IDLE;
        closeIndicator();
        return result;
    }

    public boolean finish(MotionEvent up) {
        if (state != State.DIRECT || up == null) {
            cancel();
            return false;
        }
        return finishDirect(up.getRawX(), up.getRawY());
    }

    public void cancel() {
        boolean active = state == State.DIRECT;
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        selectionX = selectionY = Float.NaN;
        closeIndicator();
        if (active) DiagnosticLog.i(context, "FV_SELECT", "DIRECT_CANCEL");
    }

    private void ensureIndicator() {
        if (indicatorOverlay == null) indicatorOverlay = new FvActionHintOverlay(context);
    }

    private void closeIndicator() {
        if (indicatorOverlay != null) indicatorOverlay.close();
        indicatorOverlay = null;
    }
}
