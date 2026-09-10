package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * Two independent FV visual layers are preserved:
 * 1) the 15dp red/yellow circle_focus probe whose centre is the exact hit-test point;
 * 2) the 24dp operation-state hint which tells the user whether release will extract text,
 *    capture a View/image, or capture a screenshot region.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;

    private ViewHoverOverlay overlay;
    private FvProbePointOverlay probeOverlay;
    private FvOperationHintOverlay operationOverlay;
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

    /** Snapshot FV's real small-icon origin while ACTION_DOWN still belongs to that Window. */
    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            pointTransformer.begin(e);
            PointF p = pointTransformer.transform(e);
            selectionX = p.x;
            selectionY = p.y;
            DiagnosticLog.i(context, "FV_SELECT", "DOWN probe="
                    + Math.round(selectionX) + "," + Math.round(selectionY)
                    + " iconSide=" + (pointTransformer.gestureLeftSide() ? "L" : "R"));
        } else if (action == MotionEvent.ACTION_MOVE && state == State.DIRECT) {
            updateDirect(e.getRawX(), e.getRawY());
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
        }
    }

    /** Moving state: only the RED exact-point probe is shown. */
    public PointF showProbe(float rawX, float rawY) {
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        ensureProbe();
        if (probeOverlay != null) probeOverlay.setTracking();
        hideOperationHint();
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        return shown;
    }

    public void hideProbe() {
        if (probeOverlay != null) probeOverlay.hide();
        hideOperationHint();
    }

    /**
     * Delayed move-idle state: the probe turns YELLOW and FV's separate operation hint appears.
     */
    public boolean activateDirect(float rawX, float rawY) {
        if (accessibility == null) return false;
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        overlay = new ViewHoverOverlay(context);
        if (!overlay.available()) {
            overlay = null;
            hideProbe();
            return false;
        }

        overlay.begin();
        state = State.DIRECT;

        ensureProbe();
        if (probeOverlay != null) probeOverlay.setReady();

        // Critical FV invariant: visible cross centre == selection layer Point == View hit-test Point.
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        overlay.beginDirect(selectionX, selectionY);
        updateOperationHint(rawX, rawY);

        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_ENTER raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " focusHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " probe=YELLOW op=" + currentOperationMode());
        return true;
    }

    public void updateDirect(float rawX, float rawY) {
        if (state != State.DIRECT || overlay == null) return;
        ensureProbe();
        if (probeOverlay != null) probeOverlay.setReady();
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        overlay.updateDirect(selectionX, selectionY);
        updateOperationHint(rawX, rawY);
    }

    private PointF showProbeAt(PointF p) {
        if (p == null) return new PointF();
        ensureProbe();
        if (probeOverlay == null) return new PointF(Math.round(p.x), Math.round(p.y));
        return probeOverlay.showAt(Math.round(p.x), Math.round(p.y));
    }

    /** Same-touch ACTION_UP: execute exactly the operation state shown immediately before release. */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);
        boolean region = overlay != null && overlay.isRegionMode();
        boolean hadTarget = overlay != null && overlay.hasCandidate();
        FvOperationHintOverlay.Mode op = currentOperationMode();
        boolean result = overlay != null && overlay.finishDirect();
        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_UP region=" + region
                + " target=" + hadTarget + " result=" + result + " focusHit="
                + Math.round(selectionX) + "," + Math.round(selectionY) + " op=" + op);
        overlay = null;
        state = State.IDLE;
        closeVisuals();
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
        closeVisuals();
        if (active) DiagnosticLog.i(context, "FV_SELECT", "DIRECT_CANCEL");
    }

    private FvOperationHintOverlay.Mode currentOperationMode() {
        if (overlay == null) return FvOperationHintOverlay.Mode.SCREENSHOT;
        if (overlay.isRegionMode()) return FvOperationHintOverlay.Mode.SCREENSHOT;

        ScreenCandidate candidate = overlay.currentCandidate();
        if (candidate == null) return FvOperationHintOverlay.Mode.SCREENSHOT;
        if (candidate.type() == ScreenCandidate.Type.TEXT && candidate.hasText()) {
            return FvOperationHintOverlay.Mode.TEXT;
        }
        if (candidate.type() == ScreenCandidate.Type.NON_TEXT) {
            return FvOperationHintOverlay.Mode.IMAGE;
        }
        // A ROOT/fullscreen container behaves like FV's screen-capture fallback, not OCR.
        return FvOperationHintOverlay.Mode.SCREENSHOT;
    }

    private void updateOperationHint(float rawX, float rawY) {
        if (state != State.DIRECT || overlay == null) {
            hideOperationHint();
            return;
        }
        if (operationOverlay == null) operationOverlay = new FvOperationHintOverlay(context);
        RectF icon = pointTransformer.iconBoundsForRaw(rawX, rawY);
        operationOverlay.show(currentOperationMode(), icon, pointTransformer.gestureLeftSide());
    }

    private void hideOperationHint() {
        if (operationOverlay != null) operationOverlay.hide();
    }

    private void ensureProbe() {
        if (probeOverlay == null) probeOverlay = new FvProbePointOverlay(context);
    }

    private void closeVisuals() {
        if (probeOverlay != null) probeOverlay.close();
        probeOverlay = null;
        if (operationOverlay != null) operationOverlay.close();
        operationOverlay = null;
    }
}
