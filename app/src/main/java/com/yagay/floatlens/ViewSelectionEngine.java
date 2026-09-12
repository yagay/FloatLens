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
 * The critical FV invariant is that m2/g (the direct region/select layer) is usable immediately and
 * does not wait for Accessibility. FV supplies Accessibility results to that layer later through
 * setAccessiblityResult(...). FloatLens mirrors that architecture here: DIRECT/region geometry and
 * the probe/hint are always main-thread/lightweight, while the expensive target tree is prepared on
 * a worker and attached only when ready.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    // FV m2/g posts its region action 5ms after the selection/helper windows are removed.
    private static final long FL_RELEASE_ACTION_DELAY_MS = 5L;
    // FV FooViewService$c3: after READY, leaving the ±3dp box returns selection visuals to red and
    // re-arms the ordinary 400ms stability timer. This only drives visual readiness here; release
    // action semantics remain unchanged.
    private static final long FL_VISUAL_READY_DELAY_MS = 400L;
    private static final float FL_VISUAL_READY_AXIS_SLOP_DP = 3f;

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
    private final float visualReadyAxisSlopPx;

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

    /** Single owner for FV red TRACKING / yellow READY visual state. */
    private SelectionVisualState visualState = SelectionVisualState.TRACKING;
    private float visualReadyAnchorX = Float.NaN, visualReadyAnchorY = Float.NaN;
    private final Runnable visualReadyRunnable = () -> {
        if (state != State.DIRECT || Float.isNaN(selectionX) || Float.isNaN(selectionY)) return;
        visualReadyAnchorX = selectionX;
        visualReadyAnchorY = selectionY;
        setVisualState(SelectionVisualState.READY, "stable_400ms");
    };

    public ViewSelectionEngine(Context c) {
        this(c, null);
    }

    /**
     * ownerIcon is intentionally retained in the constructor for call-site compatibility. FV's
     * pointer_op_hint does not use the owner FloatIconView at all; its anchor is m2/g.x (pen window).
     */
    public ViewSelectionEngine(Context c, FloatIconView ownerIcon) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        FloatSettings fs = new FloatSettings(context);
        float density = context.getResources().getDisplayMetrics().density;
        float px = fs.sizeDp() * density;
        pointTransformer = new SelectionPointTransformer(context, px, px);
        regionStartSlopPx = Math.max(1f, ViewConfiguration.get(context).getScaledTouchSlop());
        visualReadyAxisSlopPx = Math.max(1f, FL_VISUAL_READY_AXIS_SLOP_DP * density);
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
            // Starting a new pointer/selection interaction owns the UI from this point forward.
            // Any OCR callback from an older screenshot must not be allowed to open a stale popup.
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

    /**
     * Free-moving state. MOVE only updates the small probe. No target traversal occurs here.
     */
    public PointF showProbe(float rawX, float rawY) {
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);

        setVisualState(SelectionVisualState.TRACKING, "free_move");
        PointF shown = showProbeAt(transformed);
        hideOperationHint();

        selectionX = shown.x;
        selectionY = shown.y;

        // If asynchronous targets are already ready, cached hit-testing is cheap.
        if (overlay != null) overlay.update(selectionX, selectionY);
        return shown;
    }

    public void hideProbe() {
        if (probeOverlay != null) probeOverlay.hide();
        probeOverlay = null;
        if (pointerHintOverlay != null) pointerHintOverlay.close();
        pointerHintOverlay = null;
    }

    /**
     * FV dwell expiry: enter DIRECT immediately. Accessibility preparation is optional and async.
     * This means video SurfaceView/TextureView trees can never delay region screenshot activation.
     */
    public boolean activateDirect(float rawX, float rawY) {
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        state = State.DIRECT;
        directRegionMode = false;
        directRegion.setEmpty();
        closeDirectRegionFrame();
        cancelVisualReadyTimer();

        // Critical FV invariant: visible cross centre == selection Point.
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        directStartX = selectionX;
        directStartY = selectionY;

        // FloatIconView already waited FV's initial 400ms before entering DIRECT, so the selection
        // is READY immediately here. Subsequent movement beyond ±3dp returns it to TRACKING.
        visualReadyAnchorX = selectionX;
        visualReadyAnchorY = selectionY;
        setVisualState(SelectionVisualState.READY, "direct_enter_after_initial_dwell");

        // Show SCREENSHOT operation immediately, before target results exist.
        updateOperationHint();
        prepareTargetsAsync();

        DiagnosticLog.i(context, "FL_SELECT", "DIRECT_ENTER_IMMEDIATE raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " focusHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " slop=" + Math.round(regionStartSlopPx)
                + " visualAxisSlopPx=" + Math.round(visualReadyAxisSlopPx)
                + " accessibilityAsync=" + (accessibility != null));
        return true;
    }

    public void updateDirect(float rawX, float rawY) {
        if (state != State.DIRECT) return;

        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        updateVisualStability(selectionX, selectionY);

        if (Float.isNaN(directStartX) || Float.isNaN(directStartY)) {
            directStartX = selectionX;
            directStartY = selectionY;
        }

        float dx = selectionX - directStartX;
        float dy = selectionY - directStartY;
        if (!directRegionMode && dx * dx + dy * dy >= regionStartSlopPx * regionStartSlopPx) {
            directRegionMode = true;
            // Region drag always wins over a late View candidate, exactly like FV m2/g.
            if (overlay != null) {
                overlay.cancel();
                overlay = null;
            }
            targetGeneration++;
            if (directRegionFrame == null) directRegionFrame = new FlRegionFrameOverlay(context);
            directRegionFrame.setVisualState(visualState);
            DiagnosticLog.i(context, "FL_REGION", "ENTER immediate-frame dx=" + Math.round(dx)
                    + " dy=" + Math.round(dy) + " state=" + visualState);
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
                directRegionFrame.setVisualState(visualState);
                directRegionFrame.show(directRegion);
            }
        } else if (overlay != null) {
            // Target tree was delivered asynchronously; cached hit-testing only.
            overlay.update(selectionX, selectionY);
        }

        updateOperationHint();
    }

    /**
     * Prepare View/text candidates exactly as an optional late result. FV's m2/g is already active
     * when setAccessiblityResult(...) arrives; this worker/main handoff mirrors that ordering.
     */
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
                    // begin() performs only candidate collection/model preparation; it attaches no UI.
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
                overlay.setVisualState(visualState);
                overlay.update(selectionX, selectionY);
                updateOperationHint();
                DiagnosticLog.i(context, "FL_TREE_CACHE", "ASYNC_APPLY gen=" + generation
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                        + " region=false state=" + visualState);
            });
        });
    }

    /**
     * FV m2/g.D() moves float_pen_view and pointer_op_hint together. Keep the sibling hint window
     * synchronized here on every point update, even while its internal icon is hidden.
     */
    private PointF showProbeAt(PointF p) {
        if (p == null) return new PointF();
        ensureProbe();
        if (probeOverlay == null) return new PointF(Math.round(p.x), Math.round(p.y));

        probeOverlay.setVisualState(visualState);
        PointF shown = probeOverlay.showAt(Math.round(p.x), Math.round(p.y));
        ensurePointerHint();
        if (pointerHintOverlay != null && probeOverlay.isAttached()) {
            pointerHintOverlay.syncToProbeWindow(probeOverlay.windowX(), probeOverlay.windowY());
        }
        return shown;
    }

    /**
     * Same-touch ACTION_UP. Mirrors FV m2/g: snapshot the selection, remove every helper window,
     * then post the actual region action 5ms later.
     */
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
        cancelVisualReadyTimer();
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        closeDirectRegionFrame();
        directRegionMode = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        resetVisualState();
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
                + " visualState=" + visualState
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
        cancelVisualReadyTimer();
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        directRegionMode = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        selectionX = selectionY = Float.NaN;
        closeDirectRegionFrame();
        resetVisualState();
        closeVisuals();
        if (active) DiagnosticLog.i(context, "FL_SELECT", "DIRECT_CANCEL");
    }

    private void updateVisualStability(float x, float y) {
        if (state != State.DIRECT) return;
        if (Float.isNaN(visualReadyAnchorX) || Float.isNaN(visualReadyAnchorY)) {
            visualReadyAnchorX = x;
            visualReadyAnchorY = y;
            return;
        }

        float dx = x - visualReadyAnchorX;
        float dy = y - visualReadyAnchorY;
        if (Math.abs(dx) <= visualReadyAxisSlopPx && Math.abs(dy) <= visualReadyAxisSlopPx) return;

        setVisualState(SelectionVisualState.TRACKING, "moved_outside_3dp");
        visualReadyAnchorX = x;
        visualReadyAnchorY = y;
        mainHandler.removeCallbacks(visualReadyRunnable);
        mainHandler.postDelayed(visualReadyRunnable, FL_VISUAL_READY_DELAY_MS);
        DiagnosticLog.i(context, "FL_SELECTION_VISUAL", "REARM x=" + Math.round(x)
                + " y=" + Math.round(y) + " dx=" + Math.round(dx) + " dy=" + Math.round(dy)
                + " axisSlopPx=" + Math.round(visualReadyAxisSlopPx)
                + " delayMs=" + FL_VISUAL_READY_DELAY_MS);
    }

    private void setVisualState(SelectionVisualState next, String reason) {
        if (next == null) next = SelectionVisualState.TRACKING;
        if (visualState == next) return;
        visualState = next;
        if (probeOverlay != null) probeOverlay.setVisualState(next);
        if (overlay != null) overlay.setVisualState(next);
        if (directRegionFrame != null) directRegionFrame.setVisualState(next);
        DiagnosticLog.i(context, "FL_SELECTION_VISUAL", "STATE " + next + " reason=" + reason);
    }

    private void cancelVisualReadyTimer() {
        mainHandler.removeCallbacks(visualReadyRunnable);
    }

    private void resetVisualState() {
        visualState = SelectionVisualState.TRACKING;
        visualReadyAnchorX = visualReadyAnchorY = Float.NaN;
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

    /**
     * FV m2/g.E() changes pointer_op_hint content. Position always comes from the + probe window.
     * Unlike the old FloatLens path, the SCREENSHOT hint does not wait for an Accessibility target.
     */
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
        if (probeOverlay == null) {
            probeOverlay = new FlProbePointOverlay(context);
            probeOverlay.setVisualState(visualState);
        }
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
