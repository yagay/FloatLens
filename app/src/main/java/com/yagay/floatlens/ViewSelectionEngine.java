package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * FV-style same-touch View / region selection engine.
 *
 * Normal dragging continuously picks Accessibility Views. Region capture remains available, but is
 * only armed when direct selection begins on empty / ROOT-like space. This prevents ordinary View
 * navigation from being stolen by the old "travel past touch slop => region" rule.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private static final long FL_RELEASE_ACTION_DELAY_MS = 5L;
    private static final long FL_VIEW_READY_DELAY_MS = 400L;
    private static final float FL_VIEW_READY_AXIS_SLOP_DP = 3f;

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float viewReadyAxisSlopPx;
    private final float regionStartSlopPx;

    private ViewHoverOverlay overlay;
    private FlProbePointOverlay probeOverlay;
    private FlPointerOperationHintOverlay pointerHintOverlay;

    /** Region capture is a separate state and never steals a gesture that started on a usable View. */
    private FlRegionFrameOverlay directRegionFrame;
    private final Rect directRegion = new Rect();
    private float directStartX = Float.NaN, directStartY = Float.NaN;
    private boolean directRegionMode;
    private boolean regionArmed;

    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;

    /** FV o1/n1.d state applies only to the selected View rectangle. */
    private SelectionVisualState viewVisualState = SelectionVisualState.TRACKING;
    private float viewReadyAnchorX = Float.NaN, viewReadyAnchorY = Float.NaN;
    private String viewReadyCandidateKey = "";
    private final Runnable viewReadyRunnable = () -> {
        if (state != State.DIRECT || directRegionMode || overlay == null) return;
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
        regionStartSlopPx = Math.max(1f, ViewConfiguration.get(context).getScaledTouchSlop());
    }

    /** FV pointer selection is available even before Accessibility connects. */
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

    /** Initial FloatIconView dwell has expired; enter FV direct selection immediately. */
    public boolean activateDirect(float rawX, float rawY) {
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        state = State.DIRECT;
        directRegionMode = false;
        regionArmed = false;
        directRegion.setEmpty();
        closeDirectRegionFrame();
        resetViewReadiness();

        ensureProbe();
        if (probeOverlay != null) probeOverlay.setReady();

        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        directStartX = selectionX;
        directStartY = selectionY;

        // Immediate point-pruned lookup. If this starts on a real View, View mode wins for the whole
        // gesture. Empty / ROOT-like space arms region capture instead.
        ensureTargetOverlay(true);
        ScreenCandidate initial = overlay == null ? null : overlay.currentCandidate();
        regionArmed = !isUsableViewCandidate(initial);
        updateOperationHint();

        DiagnosticLog.i(context, "FL_SELECT", "DIRECT_ENTER_IMMEDIATE raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " focusHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " viewAxisSlopPx=" + Math.round(viewReadyAxisSlopPx)
                + " regionSlopPx=" + Math.round(regionStartSlopPx)
                + " regionArmed=" + regionArmed
                + " pointLookup=" + (accessibility != null));
        return true;
    }

    /**
     * Continuous FV View picking. A gesture that began on a usable View stays in View mode. A gesture
     * that began on empty / broad ROOT space may become a region drag after travelling past slop.
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

        if (directRegionMode) {
            updateRegionFrame();
            updateOperationHint();
            return;
        }

        ensureTargetOverlay(false);
        if (overlay != null) {
            overlay.update(selectionX, selectionY);
            updateViewCandidateStability(selectionX, selectionY);
        }

        // If the gesture started in blank space but reaches a real View before crossing the region
        // threshold, prefer that View and permanently disarm region mode for this touch stream.
        ScreenCandidate current = overlay == null ? null : overlay.currentCandidate();
        if (regionArmed && isUsableViewCandidate(current)) {
            regionArmed = false;
            DiagnosticLog.i(context, "FL_REGION", "DISARM reason=usable_view type=" + current.type());
        }

        if (regionArmed) {
            float dx = selectionX - directStartX;
            float dy = selectionY - directStartY;
            if (dx * dx + dy * dy >= regionStartSlopPx * regionStartSlopPx) {
                enterRegionMode(dx, dy);
                updateRegionFrame();
            }
        }

        updateOperationHint();
    }

    private void enterRegionMode(float dx, float dy) {
        if (directRegionMode) return;
        directRegionMode = true;
        regionArmed = false;
        cancelViewReadyTimer();
        if (overlay != null) {
            overlay.cancel();
            overlay = null;
        }
        if (directRegionFrame == null) directRegionFrame = new FlRegionFrameOverlay(context);
        DiagnosticLog.i(context, "FL_REGION", "ENTER blank-space-region dx=" + Math.round(dx)
                + " dy=" + Math.round(dy));
    }

    private void updateRegionFrame() {
        if (!directRegionMode) return;
        int l = Math.round(Math.min(directStartX, selectionX));
        int t = Math.round(Math.min(directStartY, selectionY));
        int r = Math.round(Math.max(directStartX, selectionX));
        int b = Math.round(Math.max(directStartY, selectionY));
        if (r <= l || b <= t) return;
        if (directRegion.left != l || directRegion.top != t
                || directRegion.right != r || directRegion.bottom != b) {
            directRegion.set(l, t, r, b);
            if (directRegionFrame == null) directRegionFrame = new FlRegionFrameOverlay(context);
            directRegionFrame.show(directRegion);
        }
    }

    /** Create the View layer once and let it refresh only the candidate chain at the current point. */
    private void ensureTargetOverlay(boolean forceBegin) {
        if (accessibility == null || state != State.DIRECT || directRegionMode) return;
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

    /** A broad ROOT/fullscreen fallback is not a meaningful View target and can start region mode. */
    private boolean isUsableViewCandidate(ScreenCandidate candidate) {
        if (candidate == null || candidate.bounds().isEmpty()) return false;
        if (candidate.type() == ScreenCandidate.Type.ROOT || candidate.fullscreenLike()) return false;
        if (candidate.type() == ScreenCandidate.Type.TEXT
                || candidate.type() == ScreenCandidate.Type.NON_TEXT) return true;
        if (candidate.type() != ScreenCandidate.Type.VIEW) return false;

        if (candidate.hasText()) return true;
        if (candidate.viewId() != null && !candidate.viewId().isBlank()) return true;
        if (candidate.clickable() || candidate.editable() || candidate.focusable()) return true;

        String cls = candidate.className() == null ? "" : candidate.className().trim();
        return !cls.isEmpty() && !"android.view.View".equals(cls);
    }

    /** FV FooViewService -> o1/n1.d(false): every newly selected View starts red. */
    private void onViewCandidateChanged(ScreenCandidate candidate) {
        cancelViewReadyTimer();
        setViewVisualState(SelectionVisualState.TRACKING, "candidate_changed");
        if (candidate == null) {
            viewReadyCandidateKey = "";
            viewReadyAnchorX = viewReadyAnchorY = Float.NaN;
            return;
        }
        viewReadyCandidateKey = candidate.stableKey();
        viewReadyAnchorX = selectionX;
        viewReadyAnchorY = selectionY;
        armViewReadyTimer("candidate_changed");
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
        if (directRegionMode || overlay == null || overlay.currentCandidate() == null) return;
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

    /** Same-touch ACTION_UP: snapshot the current View/region, remove helpers, execute after 5ms. */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);

        final boolean region = directRegionMode && !directRegion.isEmpty();
        final ScreenCandidate candidate = region || overlay == null ? null : overlay.currentCandidate();
        final FlPointerOperationHintOverlay.Mode op = currentOperationMode();
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

        resetViewReadiness();
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        directRegionMode = false;
        regionArmed = false;
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
                    // Generic VIEW is a valid FV target; capture exactly its rectangle.
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
        directRegionMode = false;
        regionArmed = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        selectionX = selectionY = Float.NaN;
        closeDirectRegionFrame();
        closeVisuals();
        if (active) DiagnosticLog.i(context, "FL_SELECT", "DIRECT_CANCEL");
    }

    private FlPointerOperationHintOverlay.Mode currentOperationMode() {
        if (directRegionMode) return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
        if (overlay == null) return FlPointerOperationHintOverlay.Mode.SCREENSHOT;

        ScreenCandidate candidate = overlay.currentCandidate();
        if (candidate == null) return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
        if (candidate.type() == ScreenCandidate.Type.TEXT && candidate.hasText()) {
            return FlPointerOperationHintOverlay.Mode.TEXT;
        }
        if (candidate.type() == ScreenCandidate.Type.NON_TEXT) {
            return FlPointerOperationHintOverlay.Mode.IMAGE;
        }
        return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
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
