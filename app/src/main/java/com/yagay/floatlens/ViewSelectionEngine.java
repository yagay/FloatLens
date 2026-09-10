package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * The 15dp probe follows the same transformed Point used for View hit testing. FV exposes two
 * visible probe states: red while the icon is still being moved, and yellow after the delayed
 * move-idle/direct-selection trigger has fired. Yellow remains active while selecting a View or
 * drawing a rectangular region.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;

    private ViewHoverOverlay overlay;
    private FvProbePointOverlay probeOverlay;
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
     * FV moving state: show a RED cross at the transformed point. FloatIconView rearms its delayed
     * selector while movement continues; the cross remains red until that delay actually fires.
     */
    public PointF showProbe(float rawX, float rawY) {
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        ensureProbe();
        if (probeOverlay != null) probeOverlay.setTracking();
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        return shown;
    }

    public void hideProbe() {
        if (probeOverlay != null) probeOverlay.hide();
    }

    /**
     * FV delayed move-idle state: the red cross turns YELLOW. From this moment the same yellow probe
     * drives View highlighting and any subsequent rectangular region selection.
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

        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_ENTER raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " focusHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " probe=YELLOW");
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
    }

    private PointF showProbeAt(PointF p) {
        if (p == null) return new PointF();
        ensureProbe();
        if (probeOverlay == null) return new PointF(Math.round(p.x), Math.round(p.y));
        return probeOverlay.showAt(Math.round(p.x), Math.round(p.y));
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
                + " target=" + hadTarget + " result=" + result + " focusHit="
                + Math.round(selectionX) + "," + Math.round(selectionY));
        overlay = null;
        state = State.IDLE;
        closeProbe();
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
        closeProbe();
        if (active) DiagnosticLog.i(context, "FV_SELECT", "DIRECT_CANCEL");
    }

    private void ensureProbe() {
        if (probeOverlay == null) probeOverlay = new FvProbePointOverlay(context);
    }

    private void closeProbe() {
        if (probeOverlay != null) probeOverlay.close();
        probeOverlay = null;
    }
}
