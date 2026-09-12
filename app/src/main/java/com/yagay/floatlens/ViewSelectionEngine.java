package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FV-style same-touch selection engine.
 *
 * Region dragging and selected-View readiness are intentionally separate. FV's FooViewService owns
 * the dwell/re-arm state and calls o1/n1.d(false/true) on the selected View rectangle. FloatLens
 * mirrors that relationship: ViewHoverOverlay is passive; this engine owns the 400ms / ±3dp state.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private static final long FL_RELEASE_ACTION_DELAY_MS = 5L;
    private static final long FL_VIEW_READY_DELAY_MS = 400L;
    private static final float FL_VIEW_READY_AXIS_SLOP_DP = 3f;

    private static final ExecutorService TARGET_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-FL-targets");
        t.setDaemon(true);
        return t;
    });

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float regionStartSlopPx;
    private final float viewReadyAxisSlopPx;

    /** Optional View/text target layer. It is never required for region screenshot. */
    private ViewHoverOverlay overlay;
    private FlProbePointOverlay probeOverlay;
    private FlPointerOperationHintOverlay pointerHintOverlay;

    /** Immediate FV m2/g-style region state, independent of Accessibility readiness. */
    private FlRegionFrameOverlay directRegionFrame;
    private final Rect directRegion = new Rect();
    private float directStartX = Float.NaN, directStartY = Float.NaN;
    private boolean directRegionMode;

    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;
    private long targetGeneration;

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
        regionStartSlopPx = Math.max(1f, ViewConfiguration.get(context).getScaledTouchSlop());
        viewReadyAxisSlopPx = Math.max(1f, FL_VIEW_READY_AXIS_SLOP_DP * density);
    }

    /** FV m2/g.v() is unconditional: region/select mode is not gated by Accessibility. */
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

    /** Initial FloatIconView dwell has expired; enter direct selection immediately. */
    public boolean activateDirect(float rawX, float rawY) {
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        state = State.DIRECT;
        directRegionMode = false;
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

        updateOperationHint();
        prepareTargetsAsync();

        DiagnosticLog.i(context, "FL_SELECT", "DIRECT_ENTER_IMMEDIATE raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " focusHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " slop=" + Math.round(regionStartSlopPx)
                + " viewAxisSlopPx=" + Math.round(viewReadyAxisSlopPx)
                + " accessibilityAsync=" + (accessibility != null));
        return true;
    }

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

        float dx = selectionX - directStartX;
        float dy = selectionY - directStartY;
        if (!directRegionMode && dx * dx + dy * dy >= regionStartSlopPx * regionStartSlopPx) {
            directRegionMode = true;
            cancelViewReadyTimer();
            if (overlay != null) {
                overlay.cancel();
                overlay = null;
            }
            targetGeneration++;
            if (directRegionFrame == null) directRegionFrame = new FlRegionFrameOverlay(context);
            DiagnosticLog.i(context, "FL_REGION", "ENTER fixed-yellow-frame dx=" + Math.round(dx)
                    + " dy=" + Math.round(dy));
        }

        if (directRegionMode) {
            int l = Math.round(Math.min(directStartX, selectionX));
            int t = Math.round(Math.min(directStartY, selectionY));
            int r = Math.round(Math.max(directStartX, selectionX));
            int b = Math.round(Math.max(directStartY, selectionY));
            if (directRegion.left != l || directRegion.top != t
                    || directRegion.right != r || directRegion.bottom != b) {
                directRegion.set(l, t, r, b);
                if (directRegionFrame == null) directRegionFrame = new FlRegionFrameOverlay(context);
                directRegionFrame.show(directRegion);
            }
        } else if (overlay != null) {
            overlay.update(selectionX, selectionY);
            updateViewCandidateStability(selectionX, selectionY);
        }

        updateOperationHint();
    }

    /** Accessibility candidates arrive as an optional late result, after direct selection is active. */
    private void prepareTargetsAsync() {
        if (accessibility == null || state != State.DIRECT || directRegionMode) return;
        final long generation = ++targetGeneration;
        final long started = SystemClock.elapsedRealtime();
        DiagnosticLog.i(context, "FL_TREE_CACHE", "ASYNC_START gen=" + generation);

        TARGET_EXECUTOR.execute(() -> {
            ViewHoverOverlay prepared = null;
            Throwable error = null;
            try {
                ViewHoverOverlay next = new ViewHoverOverlay(context);
                if (next.available()) {
                    next.begin();
                    prepared = next;
                }
            } catch (Throwable t) {
                error = t;
            }

            final ViewHoverOverlay ready = prepared;
            final Throwable failure = error;
            mainHandler.post(() -> {
                if (generation != targetGeneration || state != State.DIRECT || directRegionMode) {
                    if (ready != null) ready.cancel();
                    DiagnosticLog.i(context, "FL_TREE_CACHE", "ASYNC_DROP gen=" + generation
                            + " current=" + targetGeneration + " region=" + directRegionMode);
                    return;
                }
                if (failure != null || ready == null) {
                    DiagnosticLog.i(context, "FL_TREE_CACHE", "ASYNC_FAILED gen=" + generation
                            + " error=" + (failure == null ? "unavailable" : failure));
                    return;
                }

                if (overlay != null) overlay.cancel();
                overlay = ready;
                overlay.setCandidateListener(this::onViewCandidateChanged);
                overlay.setVisualState(SelectionVisualState.TRACKING);
                overlay.update(selectionX, selectionY);
                updateOperationHint();
                DiagnosticLog.i(context, "FL_TREE_CACHE", "ASYNC_APPLY gen=" + generation
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                        + " region=false fvViewState=" + viewVisualState);
            });
        });
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

    /** FV c3: leave the ±3dp stable box -> d(false), then re-arm the 400ms runnable. */
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
        if (overlay == null || overlay.currentCandidate() == null || directRegionMode) return;
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

    /** Same-touch ACTION_UP: snapshot, remove helpers, then execute after FV's 5ms delay. */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);

        final boolean region = directRegionMode && !directRegion.isEmpty();
        final ScreenCandidate candidate = overlay == null ? null : overlay.currentCandidate();
        final FlPointerOperationHintOverlay.Mode op = currentOperationMode();
        final Rect bounds;
        final ViewNodeCandidate view;
        final String text;

        if (op == FlPointerOperationHintOverlay.Mode.SCREENSHOT) {
            bounds = region ? new Rect(directRegion)
                    : candidate == null ? new Rect() : candidate.bounds();
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

        targetGeneration++;
        resetViewReadiness();
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        closeDirectRegionFrame();
        directRegionMode = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        closeVisuals();

        final boolean result = !bounds.isEmpty();
        if (result) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (op == FlPointerOperationHintOverlay.Mode.SCREENSHOT) {
                    ScreenshotController.captureBoundsForRegion(context, bounds);
                } else if (op == FlPointerOperationHintOverlay.Mode.TEXT) {
                    ScreenshotController.captureBoundsForViewCandidate(context, bounds, view, text);
                } else {
                    ScreenshotController.captureBoundsForVisualCandidate(context, bounds, view);
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
        targetGeneration++;
        resetViewReadiness();
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        directRegionMode = false;
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
