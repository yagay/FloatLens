package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * Two independent FV visual layers are preserved:
 * 1) the 15dp red/yellow circle_focus probe whose centre is the exact hit-test point;
 * 2) the View highlight layer, which follows MOVE immediately; the delayed 400ms transition only
 *    changes the probe/state into DIRECT selection and reveals the operation hint.
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

    /**
     * Moving state: RED probe plus live View hit-testing/highlight. This is deliberately active
     * before the 400ms dwell timer fires, matching FV where the View under circle_focus is visible
     * while the icon is still moving.
     */
    public PointF showProbe(float rawX, float rawY) {
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        ensureProbe();
        if (probeOverlay != null) probeOverlay.setTracking();
        hideOperationHint();
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;

        if (accessibility != null) {
            ensureHoverOverlay();
            if (overlay != null) overlay.update(selectionX, selectionY);
        }
        return shown;
    }

    public void hideProbe() {
        if (probeOverlay != null) probeOverlay.hide();
        hideOperationHint();
    }

    /** Delayed move-idle state: keep the existing highlight, turn probe YELLOW and enter DIRECT. */
    public boolean activateDirect(float rawX, float rawY) {
        if (accessibility == null) return false;
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        ensureHoverOverlay();
        if (overlay == null || !overlay.available()) {
            if (overlay != null) overlay.cancel();
            overlay = null;
            hideProbe();
            return false;
        }

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

    /**
     * Same-touch ACTION_UP. The operation shown by the FV 24dp hint is the operation executed.
     * This avoids the old ambiguous "recognize" release path where everything looked like OCR.
     */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);

        boolean region = overlay != null && overlay.isRegionMode();
        ScreenCandidate candidate = overlay == null ? null : overlay.currentCandidate();
        FvOperationHintOverlay.Mode op = currentOperationMode();
        boolean result = false;

        if (overlay != null) {
            if (op == FvOperationHintOverlay.Mode.SCREENSHOT) {
                Rect bounds = region ? overlay.currentRegion()
                        : candidate == null ? new Rect() : candidate.bounds();
                overlay.cancel();
                if (!bounds.isEmpty()) {
                    ScreenshotController.captureBoundsForRegion(context, bounds);
                    result = true;
                }
            } else if (candidate != null && !candidate.bounds().isEmpty()) {
                Rect bounds = candidate.bounds();
                ViewNodeCandidate view = candidate.toViewNodeCandidate();
                String text = candidate.hasText() ? candidate.text() : "";
                overlay.cancel();
                if (op == FvOperationHintOverlay.Mode.TEXT) {
                    ScreenshotController.captureBoundsForViewCandidate(context, bounds, view, text);
                } else {
                    ScreenshotController.captureBoundsForVisualCandidate(context, bounds, view);
                }
                result = true;
            } else {
                overlay.cancel();
            }
        }

        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_UP region=" + region
                + " target=" + (candidate != null) + " result=" + result + " focusHit="
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

    private void ensureHoverOverlay() {
        if (overlay != null || accessibility == null) return;
        ViewHoverOverlay next = new ViewHoverOverlay(context);
        if (!next.available()) return;
        next.begin();
        overlay = next;
        DiagnosticLog.i(context, "FV_SELECT", "HOVER_ARM live=true");
    }

    private void closeVisuals() {
        if (probeOverlay != null) probeOverlay.close();
        probeOverlay = null;
        if (operationOverlay != null) operationOverlay.close();
        operationOverlay = null;
    }
}
