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
import java.util.concurrent.Future;

/**
 * Same-touch Direct selection engine.
 *
 * <p>One Accessibility snapshot is prepared as soon as a touch starts. The 400 ms dwell only
 * decides when Direct owns the gesture; it no longer starts a second late tree scan. Once Direct is
 * READY, TEXT/VIEW/IMAGE routing is driven from the same cached snapshot and MOVE is geometry-only.</p>
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private static final long FL_RELEASE_ACTION_DELAY_MS = 5L;

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

    /** Active Direct target layer plus one prewarmed snapshot waiting for Direct ownership. */
    private ViewHoverOverlay overlay;
    private ViewHoverOverlay preparedOverlay;
    private FlProbePointOverlay probeOverlay;
    private FlPointerOperationHintOverlay pointerHintOverlay;

    private FlRegionFrameOverlay directRegionFrame;
    private final Rect directRegion = new Rect();
    private float directStartX = Float.NaN, directStartY = Float.NaN;
    private boolean directRegionMode;

    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;
    private long targetGeneration;
    private Future<?> targetFuture;

    public ViewSelectionEngine(Context c) { this(c, null); }

    /** ownerIcon is retained for source compatibility; probe geometry owns pointer positioning. */
    public ViewSelectionEngine(Context c, FloatIconView ownerIcon) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        FloatSettings fs = new FloatSettings(context);
        float density = context.getResources().getDisplayMetrics().density;
        float px = fs.sizeDp() * density;
        pointTransformer = new SelectionPointTransformer(context, px, px);
        regionStartSlopPx = Math.max(1f, ViewConfiguration.get(context).getScaledTouchSlop());
    }

    /** Region selection remains usable even if Accessibility is unavailable. */
    public boolean available() { return true; }
    public boolean isActive() { return state == State.DIRECT; }
    public State state() { return state; }

    /** Snapshot the icon origin and immediately prewarm the one candidate tree for this touch. */
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
            prepareTargetsAsync();
            DiagnosticLog.i(context, "FL_SELECT", "DOWN probe="
                    + Math.round(selectionX) + "," + Math.round(selectionY)
                    + " iconSide=" + (pointTransformer.gestureLeftSide() ? "L" : "R")
                    + " accessibility=" + (accessibility != null)
                    + " prewarm=true");
        } else if (action == MotionEvent.ACTION_MOVE && state == State.DIRECT) {
            updateDirect(e.getRawX(), e.getRawY());
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
        }
    }

    /** Free-moving phase: only move the probe; candidate snapshot continues in the background. */
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

    /** The dwell gate expired: take Direct ownership and consume the already-prepared snapshot. */
    public boolean activateDirect(float rawX, float rawY) {
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        state = State.DIRECT;
        directRegionMode = false;
        directRegion.setEmpty();
        closeDirectRegionFrame();

        ensureProbe();
        if (probeOverlay != null) probeOverlay.setReady();

        PointF transformed = pointTransformer.transformRaw(rawX, rawY);
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;
        directStartX = selectionX;
        directStartY = selectionY;

        if (preparedOverlay != null) {
            ViewHoverOverlay ready = preparedOverlay;
            preparedOverlay = null;
            installOverlay(ready, "prewarmed");
        } else if (targetFuture == null && accessibility != null) {
            // A cancelled/failed prewarm may be retried, but there is still only one snapshot owner.
            prepareTargetsAsync();
        }
        updateOperationHint();

        DiagnosticLog.i(context, "FL_SELECT", "DIRECT_ENTER raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " focusHit=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + " slop=" + Math.round(regionStartSlopPx)
                + " cache=" + (overlay != null ? "ready" : targetFuture != null ? "warming" : "none"));
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
            cancelTargetPreparation("enter_region");
            clearCandidateOverlays();
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
        }
        updateOperationHint();
    }

    /** Prepare exactly one interruption-aware Accessibility snapshot for the current touch. */
    private void prepareTargetsAsync() {
        if (accessibility == null || directRegionMode) return;
        cancelTargetPreparation("replace");
        if (preparedOverlay != null) {
            preparedOverlay.cancel();
            preparedOverlay = null;
        }
        final long generation = ++targetGeneration;
        final long started = SystemClock.elapsedRealtime();
        DiagnosticLog.i(context, "FL_TREE_CACHE", "PREWARM_START gen=" + generation
                + " state=" + state);

        targetFuture = TARGET_EXECUTOR.submit(() -> {
            ViewHoverOverlay prepared = null;
            Throwable error = null;
            try {
                if (Thread.currentThread().isInterrupted()) return;
                ViewHoverOverlay next = new ViewHoverOverlay(context);
                if (next.available()) {
                    next.begin();
                    if (Thread.currentThread().isInterrupted()) {
                        next.cancel();
                        DiagnosticLog.i(context, "FL_TREE_CACHE", "PREWARM_INTERRUPTED gen=" + generation);
                        return;
                    }
                    prepared = next;
                }
            } catch (Throwable t) {
                if (!Thread.currentThread().isInterrupted()) error = t;
            }

            if (Thread.currentThread().isInterrupted()) {
                if (prepared != null) prepared.cancel();
                return;
            }

            final ViewHoverOverlay ready = prepared;
            final Throwable failure = error;
            mainHandler.post(() -> {
                if (generation != targetGeneration || directRegionMode) {
                    if (ready != null) ready.cancel();
                    DiagnosticLog.i(context, "FL_TREE_CACHE", "PREWARM_DROP gen=" + generation
                            + " current=" + targetGeneration + " region=" + directRegionMode);
                    return;
                }
                targetFuture = null;
                if (failure != null || ready == null) {
                    DiagnosticLog.i(context, "FL_TREE_CACHE", "PREWARM_FAILED gen=" + generation
                            + " error=" + (failure == null ? "unavailable" : failure));
                    return;
                }

                if (state == State.DIRECT) {
                    installOverlay(ready, "async_direct");
                } else {
                    if (preparedOverlay != null) preparedOverlay.cancel();
                    preparedOverlay = ready;
                    DiagnosticLog.i(context, "FL_TREE_CACHE", "PREWARM_READY gen=" + generation
                            + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                }
            });
        });
    }

    private void installOverlay(ViewHoverOverlay ready, String source) {
        if (ready == null || directRegionMode || state != State.DIRECT) {
            if (ready != null) ready.cancel();
            return;
        }
        if (overlay != null && overlay != ready) overlay.cancel();
        overlay = ready;
        overlay.setVisualState(SelectionVisualState.READY);
        overlay.update(selectionX, selectionY);
        updateOperationHint();
        DiagnosticLog.i(context, "FL_TREE_CACHE", "APPLY source=" + source
                + " selection=" + Math.round(selectionX) + "," + Math.round(selectionY));
    }

    private void cancelTargetPreparation(String reason) {
        Future<?> future = targetFuture;
        targetFuture = null;
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            DiagnosticLog.i(context, "FL_TREE_CACHE", "ASYNC_CANCEL reason=" + reason
                    + " success=" + cancelled);
        }
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

    /** Same-touch ACTION_UP: snapshot, remove helpers, then execute after the FL 5 ms delay. */
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

        if (region) {
            bounds = new Rect(directRegion);
            view = null;
            text = "";
        } else if (candidate != null && !candidate.bounds().isEmpty()) {
            bounds = candidate.bounds();
            view = candidate.toViewNodeCandidate();
            text = candidate.hasText() ? candidate.text() : "";
        } else {
            // Never turn a not-yet-ready candidate cache into an accidental screenshot.
            bounds = new Rect();
            view = null;
            text = "";
        }

        cancelTargetPreparation("finish");
        targetGeneration++;
        clearCandidateOverlays();
        state = State.IDLE;
        closeDirectRegionFrame();
        directRegionMode = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        closeVisuals();

        final boolean result = !bounds.isEmpty();
        if (result) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (region || op == FlPointerOperationHintOverlay.Mode.SCREENSHOT) {
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
        cancelTargetPreparation("cancel");
        targetGeneration++;
        clearCandidateOverlays();
        state = State.IDLE;
        directRegionMode = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        selectionX = selectionY = Float.NaN;
        closeDirectRegionFrame();
        closeVisuals();
        if (active) DiagnosticLog.i(context, "FL_SELECT", "DIRECT_CANCEL");
    }

    /** TEXT extracts visible text; VIEW/IMAGE captures that View; only ROOT/region means screenshot. */
    private FlPointerOperationHintOverlay.Mode currentOperationMode() {
        if (directRegionMode) return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
        if (overlay == null) return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
        ScreenCandidate candidate = overlay.currentCandidate();
        if (candidate == null || candidate.type() == ScreenCandidate.Type.ROOT) {
            return FlPointerOperationHintOverlay.Mode.SCREENSHOT;
        }
        if (candidate.type() == ScreenCandidate.Type.TEXT && candidate.hasText()) {
            return FlPointerOperationHintOverlay.Mode.TEXT;
        }
        return FlPointerOperationHintOverlay.Mode.IMAGE;
    }

    private void updateOperationHint() {
        if (state != State.DIRECT || probeOverlay == null || !probeOverlay.isAttached()) {
            hideOperationHint();
            return;
        }
        ensurePointerHint();
        if (pointerHintOverlay == null) return;
        pointerHintOverlay.show(currentOperationMode(), probeOverlay.windowX(), probeOverlay.windowY());
    }

    private void hideOperationHint() {
        if (pointerHintOverlay != null) pointerHintOverlay.hideContent();
    }

    private void clearCandidateOverlays() {
        if (overlay != null) overlay.cancel();
        overlay = null;
        if (preparedOverlay != null) preparedOverlay.cancel();
        preparedOverlay = null;
    }

    private void ensureProbe() {
        if (probeOverlay == null) probeOverlay = new FlProbePointOverlay(context);
    }

    private void ensurePointerHint() {
        if (pointerHintOverlay == null) pointerHintOverlay = new FlPointerOperationHintOverlay(context);
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
