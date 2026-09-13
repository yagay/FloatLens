package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * FV-style same-touch View + region selection engine.
 *
 * FV keeps its selected-View layer and GestureOverlay/region layer alive for the same touch stream.
 * FloatLens mirrors that model here: MOVE continuously refreshes the Accessibility candidate while
 * independently maintaining the drag rectangle. Neither tracker destroys the other. When both are
 * valid, the final operation is arbitrated on release: a View that has stayed stable long enough to
 * reach READY wins; otherwise a valid drag region wins.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private static final long FL_RELEASE_ACTION_DELAY_MS = 5L;
    private static final long FL_VIEW_READY_DELAY_MS = 400L;
    private static final float FL_VIEW_READY_AXIS_SLOP_DP = 3f;
    private static final float FL_REGION_MIN_SPAN_DP = 12f;

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float viewReadyAxisSlopPx;
    private final float regionMinSpanPx;

    /** FV o1/n1-like selected View layer. */
    private ViewHoverOverlay overlay;
    private FlProbePointOverlay probeOverlay;
    private FlPointerOperationHintOverlay pointerHintOverlay;

    /** FV m2/g-like independent region tracker. */
    private FlRegionFrameOverlay directRegionFrame;
    private final Rect directRegion = new Rect();
    private float directStartX = Float.NaN, directStartY = Float.NaN;
    private boolean regionValid;

    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;

    /** FV o1/n1.d state applies only to the selected View rectangle. */
    private SelectionVisualState viewVisualState = SelectionVisualState.TRACKING;
    private float viewReadyAnchorX = Float.NaN, viewReadyAnchorY = Float.NaN;
    private String viewReadyCandidateKey = "";
    private final Runnable viewReadyRunnable = () -> {
        if (state != State.DIRECT || overlay == null) return;
        ScreenCandidate candidate = overlay.currentCandidate();
        if (candidate == null || !candidate.stableKey().equals(viewReadyCandidateKey)) return;
        setViewVisualState(SelectionVisualState.READY, "candidate_stable_400ms");
    };

    public ViewSelectionEngine(Context c) {
        this(c, null);
    }

    /**
     * ownerIcon is retained for call-site compatibility. FV's pointer_op_hint is anchored from the
     * moving pen/probe window, not from the owner FloatIconView itself.
     */
    public ViewSelectionEngine(Context c, FloatIconView ownerIcon) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        FloatSettings fs = new FloatSettings(context);
        float density = context.getResources().getDisplayMetrics().density;
        float px = fs.sizeDp() * density;
        pointTransformer = new SelectionPointTransformer(context, px, px);
        viewReadyAxisSlopPx = Math.max(1f, FL_VIEW_READY_AXIS_SLOP_DP * density);
        regionMinSpanPx = Math.max(
                ViewConfiguration.get(context).getScaledTouchSlop(),
                FL_REGION_MIN_SPAN_DP * density);
    }

    /** FV pointer/region selection is available even before Accessibility connects. */
    public boolean available() { return true; }
    public boolean isActive() { return state == State.DIRECT; }
    public State state() { return state; }

    /** Snapshot FV's real small-icon origin while ACTION_DOWN still belongs to that Window. */
    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null) return;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            OcrEngine.invalidatePending(context, "new_float_selection");
            pointTransformer.begin(e);
            PointF p = pointTransformer.transform(e);
            selectionX = p.x;
            selectionY = p.y;
            DiagnosticLog.i(context, "FL_SELECT", "DOWN probe="
                    + Math.round(selectionX) + "," + Math.round(selectionY)
                    + " iconSide=" + (pointTransformer.gestureLeftSide() ? "L" : "R")
                    + " accessibility=" + (accessibility != null));
        } else if (action == MotionEvent.ACTION_MOVE && state == State.DIRECT) {
            updateDirect(e.getRawX(), e.getRawY());
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
        }
    }

    /** Free-moving state. MOVE only updates the small probe. */
    public PointF showProbe(float rawX, float rawY) {
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        ensureProbe();
        if (probeOverlay != null) probeOverlay.setTracking();
        PointF shown = showProbeAt(transformed);
        hideOperationHint();
        selectionX = shown.x;
        selectionY = shown.y;
        return shown;
    }

    public void hideProbe() {
        if (probeOverlay != null) probeOverlay.hide();
        probeOverlay = null;
        if (pointerHintOverlay != null) pointerHintOverlay.close();
        pointerHintOverlay = null;
    }

    /** Initial FloatIconView dwell has expired; start both FV-style trackers immediately. */
    public boolean activateDirect(float rawX, float rawY) {
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        state = State.DIRECT;
        directRegion.setEmpty();
        regionValid = false;
        closeDirectRegionFrame();
        directRegionFrame = new FlRegionFrameOverlay(context);
        directRegionFrame.prepare();
        resetViewReadiness();

        ensureProbe();
        if (probeOverlay != null) probeOverlay.setReady();

        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        directStartX = selectionX;
        directStartY = selectionY;

        // o1/n1 path: immediate point-pruned Accessibility lookup.
        ensureTargetOverlay(true);
        // m2/g path: starts from the exact same transformed point, but remains invalid until both
        // width and height exceed the minimum span.
        updateRegionTracking();
        updateRegionVisual();
        updateOperationHint();

        DiagnosticLog.i(context, "FL_SELECT", "DIRECT_ENTER_IMMEDIATE raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " focusHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " viewAxisSlopPx=" + Math.round(viewReadyAxisSlopPx)
                + " regionMinSpanPx=" + Math.round(regionMinSpanPx)
                + " pointLookup=" + (accessibility != null));
        return true;
    }

    /**
     * Same MotionEvent feeds both trackers. View picking never stops because a region becomes valid,
     * and region geometry never stops because the pointer happens to be over a View.
     */
    public void updateDirect(float rawX, float rawY) {
        if (state != State.DIRECT) return;
        ensureProbe();
        if (probeOverlay != null) probeOverlay.setReady();

        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;

        if (Float.isNaN(directStartX) || Float.isNaN(directStartY)) {
            directStartX = selectionX;
            directStartY = selectionY;
        }

        // ViewTracker.
        ensureTargetOverlay(false);
        if (overlay != null) {
            overlay.update(selectionX, selectionY);
            updateViewCandidateStability(selectionX, selectionY);
        }

        // RegionTracker. Never cancel/hide the View layer just because this becomes valid.
        updateRegionTracking();
        updateRegionVisual();
        updateOperationHint();
    }

    /** FV m2/g-style rectangle accumulation with independent validity. */
    private void updateRegionTracking() {
        if (state != State.DIRECT || Float.isNaN(directStartX) || Float.isNaN(directStartY)
                || Float.isNaN(selectionX) || Float.isNaN(selectionY)) {
            return;
        }

        int l = Math.round(Math.min(directStartX, selectionX));
        int t = Math.round(Math.min(directStartY, selectionY));
        int r = Math.round(Math.max(directStartX, selectionX));
        int b = Math.round(Math.max(directStartY, selectionY));
        Rect next = new Rect(l, t, r, b);
        boolean nextValid = next.width() >= regionMinSpanPx && next.height() >= regionMinSpanPx;
        boolean rectChanged = !directRegion.equals(next);
        boolean validChanged = regionValid != nextValid;

        if (rectChanged) directRegion.set(next);
        regionValid = nextValid;

        if (validChanged) {
            DiagnosticLog.i(context, "FL_REGION", (regionValid ? "VALID" : "INVALID")
                    + " rect=" + directRegion
                    + " minSpanPx=" + Math.round(regionMinSpanPx));
        }
    }

    /**
     * Both geometries remain alive. The yellow region frame is shown only while the current arbiter
     * would choose REGION; a READY View temporarily wins visually without deleting region geometry.
     */
    private void updateRegionVisual() {
        if (state != State.DIRECT || !shouldUseRegionNow()) {
            if (directRegionFrame != null) directRegionFrame.hide();
            return;
        }
        if (directRegion.isEmpty()) return;
        if (directRegionFrame == null) directRegionFrame = new FlRegionFrameOverlay(context);
        directRegionFrame.show(directRegion);
    }

    /** Create the View layer once; MOVE reads only the prepared Accessibility geometry cache. */
    private void ensureTargetOverlay(boolean forceBegin) {
        if (accessibility == null || state != State.DIRECT) return;
        if (overlay == null) {
            overlay = new ViewHoverOverlay(context);
            if (!overlay.available()) {
                overlay = null;
                return;
            }
            overlay.setCandidateListener(this::onViewCandidateChanged);
            overlay.setVisualState(SelectionVisualState.TRACKING);
            overlay.beginAt(selectionX, selectionY);
            DiagnosticLog.i(context, "FL_TREE_CACHE", "POINT_LAYER_READY x="
                    + Math.round(selectionX) + " y=" + Math.round(selectionY));
            return;
        }
        if (forceBegin) overlay.beginAt(selectionX, selectionY);
    }

    private boolean isUsableViewCandidate(ScreenCandidate candidate) {
        return candidate != null && !candidate.bounds().isEmpty()
                && candidate.type() != ScreenCandidate.Type.ROOT
                && !candidate.fullscreenLike();
    }

    /**
     * Conflict rule for the two simultaneously valid trackers. FV's selected-View layer has a stable
     * READY state (d(true)); preserve that intent. Before READY, a real two-axis drag is interpreted
     * as region capture. If no valid region exists, the current View remains usable immediately.
     */
    private boolean readyViewWinsConflict() {
        ScreenCandidate candidate = overlay == null ? null : overlay.currentCandidate();
        return isUsableViewCandidate(candidate) && viewVisualState == SelectionVisualState.READY;
    }

    private boolean shouldUseRegionNow() {
        return regionValid && !readyViewWinsConflict();
    }

    /** FV FooViewService -> o1/n1.d(false): every newly selected View starts red. */
    private void onViewCandidateChanged(ScreenCandidate candidate) {
        cancelViewReadyTimer();
        setViewVisualState(SelectionVisualState.TRACKING, "candidate_changed");
        if (candidate == null) {
            viewReadyCandidateKey = "";
            viewReadyAnchorX = viewReadyAnchorY = Float.NaN;
            updateRegionVisual();
            updateOperationHint();
            return;
        }
        viewReadyCandidateKey = candidate.stableKey();
        viewReadyAnchorX = selectionX;
        viewReadyAnchorY = selectionY;
        armViewReadyTimer("candidate_changed");
        updateRegionVisual();
        updateOperationHint();
    }

    /** FV c3: leave the +/-3dp stable box -> d(false), then re-arm the 400ms runnable. */
    private void updateViewCandidateStability(float x, float y) {
        if (overlay == null || overlay.currentCandidate() == null) return;
        if (Float.isNaN(viewReadyAnchorX) || Float.isNaN(viewReadyAnchorY)) {
            viewReadyAnchorX = x;
            viewReadyAnchorY = y;
            armViewReadyTimer("missing_anchor");
            return;
        }

        float dx = x - viewReadyAnchorX;
        float dy = y - viewReadyAnchorY;
        if (Math.abs(dx) <= viewReadyAxisSlopPx && Math.abs(dy) <= viewReadyAxisSlopPx) return;

        setViewVisualState(SelectionVisualState.TRACKING, "moved_outside_3dp");
        viewReadyAnchorX = x;
        viewReadyAnchorY = y;
        armViewReadyTimer("moved_outside_3dp");
        DiagnosticLog.i(context, "FL_VIEW_READY", "REARM x=" + Math.round(x)
                + " y=" + Math.round(y) + " dx=" + Math.round(dx) + " dy=" + Math.round(dy)
                + " axisSlopPx=" + Math.round(viewReadyAxisSlopPx));
    }

    private void armViewReadyTimer(String reason) {
        mainHandler.removeCallbacks(viewReadyRunnable);
        if (overlay == null || overlay.currentCandidate() == null) return;
        mainHandler.postDelayed(viewReadyRunnable, FL_VIEW_READY_DELAY_MS);
        DiagnosticLog.i(context, "FL_VIEW_READY", "ARM reason=" + reason
                + " delayMs=" + FL_VIEW_READY_DELAY_MS
                + " candidate=" + overlay.currentCandidate().type());
    }

    private void setViewVisualState(SelectionVisualState next, String reason) {
        if (next == null) next = SelectionVisualState.TRACKING;
        viewVisualState = next;
        if (overlay != null) overlay.setVisualState(next);
        DiagnosticLog.i(context, "FL_VIEW_READY", "STATE " + next + " reason=" + reason);
        updateRegionVisual();
        updateOperationHint();
    }

    private void cancelViewReadyTimer() {
        mainHandler.removeCallbacks(viewReadyRunnable);
    }

    private void resetViewReadiness() {
        cancelViewReadyTimer();
        viewVisualState = SelectionVisualState.TRACKING;
        viewReadyCandidateKey = "";
        viewReadyAnchorX = viewReadyAnchorY = Float.NaN;
    }

    /** Keep the sibling operation-hint window synchronized to the + probe. */
    private PointF showProbeAt(PointF p) {
        if (p == null) return new PointF();
        ensureProbe();
        if (probeOverlay == null) return new PointF(Math.round(p.x), Math.round(p.y));

        PointF shown = probeOverlay.showAt(Math.round(p.x), Math.round(p.y));
        ensurePointerHint();
        if (pointerHintOverlay != null && probeOverlay.isAttached()) {
            pointerHintOverlay.syncToProbeWindow(probeOverlay.windowX(), probeOverlay.windowY());
        }
        return shown;
    }

    /** Same-touch ACTION_UP: arbitrate the two still-live trackers, then execute after 5ms. */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);

        final ScreenCandidate candidate = overlay == null ? null : overlay.currentCandidate();
        final boolean region = shouldUseRegionNow();
        final FlPointerOperationHintOverlay.Mode op = region
                ? FlPointerOperationHintOverlay.Mode.SCREENSHOT
                : operationModeForCandidate(candidate);
        final Rect bounds;
        final ViewNodeCandidate view;
        final String text;

        if (region) {
            bounds = new Rect(directRegion);
            view = null;
            text = "";
        } else if (candidate != null && !candidate.bounds().isEmpty()) {
            bounds = candidate.bounds();
            view = candidate.toViewNodeCandidate();
            text = candidate.hasText() ? candidate.text() : "";
        } else {
            bounds = new Rect();
            view = null;
            text = "";
        }

        DiagnosticLog.i(context, "FL_ARBITER", "UP winner="
                + (region ? "REGION" : candidate == null ? "NONE" : "VIEW")
                + " regionValid=" + regionValid
                + " region=" + directRegion
                + " viewState=" + viewVisualState
                + " candidate=" + (candidate == null ? "none" : candidate.type()));

        resetViewReadiness();
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        regionValid = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        closeDirectRegionFrame();
        closeVisuals();

        final boolean result = !bounds.isEmpty();
        if (result) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (region) {
                    ScreenshotController.captureBoundsForRegion(context, bounds);
                } else if (op == FlPointerOperationHintOverlay.Mode.TEXT) {
                    ScreenshotController.captureBoundsForViewCandidate(context, bounds, view, text);
                } else if (op == FlPointerOperationHintOverlay.Mode.IMAGE) {
                    ScreenshotController.captureBoundsForVisualCandidate(context, bounds, view);
                } else {
                    // Generic VIEW uses the same screenshot backend but with the selected View rect.
                    ScreenshotController.captureBoundsForRegion(context, bounds);
                }
            }, FL_RELEASE_ACTION_DELAY_MS);
        }

        DiagnosticLog.i(context, "FL_SELECT", "DIRECT_UP region=" + region
                + " target=" + (candidate != null) + " result=" + result + " focusHit="
                + Math.round(selectionX) + "," + Math.round(selectionY) + " op=" + op
                + " flDelayMs=" + FL_RELEASE_ACTION_DELAY_MS);
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
        resetViewReadiness();
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        regionValid = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        selectionX = selectionY = Float.NaN;
        closeDirectRegionFrame();
        closeVisuals();
        if (active) DiagnosticLog.i(context, "FL_SELECT", "DIRECT_CANCEL");
    }

    private FlPointerOperationHintOverlay.Mode operationModeForCandidate(ScreenCandidate candidate) {
        if (candidate == null) return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
        if (candidate.type() == ScreenCandidate.Type.TEXT && candidate.hasText()) {
            return FlPointerOperationHintOverlay.Mode.TEXT;
        }
        if (candidate.type() == ScreenCandidate.Type.NON_TEXT) {
            return FlPointerOperationHintOverlay.Mode.IMAGE;
        }
        return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
    }

    private FlPointerOperationHintOverlay.Mode currentOperationMode() {
        if (shouldUseRegionNow()) return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
        ScreenCandidate candidate = overlay == null ? null : overlay.currentCandidate();
        return operationModeForCandidate(candidate);
    }

    private void updateOperationHint() {
        if (state != State.DIRECT || probeOverlay == null || !probeOverlay.isAttached()) {
            hideOperationHint();
            return;
        }

        ensurePointerHint();
        if (pointerHintOverlay == null) return;
        pointerHintOverlay.show(
                currentOperationMode(),
                probeOverlay.windowX(),
                probeOverlay.windowY());
    }

    private void hideOperationHint() {
        if (pointerHintOverlay != null) pointerHintOverlay.hideContent();
    }

    private void ensureProbe() {
        if (probeOverlay == null) probeOverlay = new FlProbePointOverlay(context);
    }

    private void ensurePointerHint() {
        if (pointerHintOverlay == null) {
            pointerHintOverlay = new FlPointerOperationHintOverlay(context);
        }
    }

    private void closeDirectRegionFrame() {
        if (directRegionFrame != null) directRegionFrame.close();
        directRegionFrame = null;
    }

    private void closeVisuals() {
        if (probeOverlay != null) probeOverlay.close();
        probeOverlay = null;
        if (pointerHintOverlay != null) pointerHintOverlay.close();
        pointerHintOverlay = null;
    }
}
