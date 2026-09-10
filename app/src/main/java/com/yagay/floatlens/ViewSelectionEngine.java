package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * The visible 24dp action indicator and the selection ProbePoint are separate concepts:
 * - the indicator stays beside the virtual moving icon and only changes drawable PLUS -> DOT;
 * - the transformed ProbePoint is used for Accessibility/View hit testing and edge compensation.
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
            DiagnosticLog.i(context, "FV_SELECT", "DOWN hotspot="
                    + Math.round(selectionX) + "," + Math.round(selectionY)
                    + " side=" + (pointTransformer.gestureLeftSide() ? "L" : "R"));
        } else if (action == MotionEvent.ACTION_MOVE && state == State.DIRECT) {
            updateDirect(e.getRawX(), e.getRawY());
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
        }
    }

    /** Drag phase: compute ProbePoint, but visually show PLUS beside the virtual icon. */
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
        PointF p = pointTransformer.transformRaw(rawX, rawY);
        selectionX = p.x;
        selectionY = p.y;
        state = State.DIRECT;

        // FV D4(state) swaps the drawable on the same 24dp helper. Do not resize it and do not
        // move it to ProbePoint. With the same raw coordinates this transition keeps exact position.
        ensureIndicator();
        showIndicatorForRaw(FvActionHintOverlay.Mode.DOT, rawX, rawY);

        overlay.beginDirect(selectionX, selectionY);
        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_ENTER raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " side=" + (pointTransformer.gestureLeftSide() ? "L" : "R"));
        return true;
    }

    public void updateDirect(float rawX, float rawY) {
        if (state != State.DIRECT || overlay == null) return;
        PointF p = pointTransformer.transformRaw(rawX, rawY);
        selectionX = p.x;
        selectionY = p.y;
        showIndicatorForRaw(FvActionHintOverlay.Mode.DOT, rawX, rawY);
        overlay.updateDirect(selectionX, selectionY);
    }

    private void showIndicatorForRaw(FvActionHintOverlay.Mode mode, float rawX, float rawY) {
        if (indicatorOverlay == null) return;
        RectF icon = pointTransformer.iconBoundsForRaw(rawX, rawY);
        boolean left = pointTransformer.gestureLeftSide();
        if (mode == FvActionHintOverlay.Mode.DOT) {
            indicatorOverlay.showDotNextTo(icon.left, icon.top, icon.width(), icon.height(), left);
        } else {
            indicatorOverlay.showPlusNextTo(icon.left, icon.top, icon.width(), icon.height(), left);
        }
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
                + " target=" + hadTarget + " result=" + result + " hotspot="
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
