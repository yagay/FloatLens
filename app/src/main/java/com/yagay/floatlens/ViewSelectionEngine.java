package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * The Accessibility tree is cached before FloatLens adds its own probe/highlight windows. MOVE then
 * updates the red/yellow probe and performs only cached rectangle hit testing.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    // FV m2/g posts its region action 5ms after the selection/helper windows are removed.
    private static final long FV_RELEASE_ACTION_DELAY_MS = 5L;

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;
    private final FloatIconView ownerIcon;

    private ViewHoverOverlay overlay;
    private FvProbePointOverlay probeOverlay;
    private FvOperationHintOverlay operationOverlay;
    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;

    public ViewSelectionEngine(Context c) {
        this(c, null);
    }

    public ViewSelectionEngine(Context c, FloatIconView ownerIcon) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        this.ownerIcon = ownerIcon;
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
     * Moving state: cache the target App's full Accessibility tree BEFORE attaching our own probe,
     * then show the RED exact-point probe and update the cached View highlight.
     */
    public PointF showProbe(float rawX, float rawY) {
        PointF transformed = pointTransformer.transformRaw(rawX, rawY);

        // Important for fullscreen/root Views: do not let FloatLens' own probe/highlight overlay
        // become the active accessibility window before the initial complete-tree snapshot.
        if (accessibility != null) ensureHoverOverlay();

        ensureProbe();
        if (probeOverlay != null) probeOverlay.setTracking();
        hideOperationHint();
        PointF shown = showProbeAt(transformed);
        selectionX = shown.x;
        selectionY = shown.y;

        if (overlay != null) overlay.update(selectionX, selectionY);
        return shown;
    }

    public void hideProbe() {
        if (probeOverlay != null) probeOverlay.hide();
        hideOperationHint();
    }

    /** Delayed move-idle state: keep the cached/highlighted target and enter DIRECT. */
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
     * Same-touch ACTION_UP. Mirrors FV m2/g: snapshot the selection, remove every helper window,
     * then post the actual region action 5ms later. No screenshot-specific compositor delay lives
     * in ScreenshotController.
     */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);

        final boolean region = overlay != null && overlay.isRegionMode();
        final ScreenCandidate candidate = overlay == null ? null : overlay.currentCandidate();
        final FvOperationHintOverlay.Mode op = currentOperationMode();
        final Rect bounds;
        final ViewNodeCandidate view;
        final String text;

        if (op == FvOperationHintOverlay.Mode.SCREENSHOT) {
            bounds = region && overlay != null ? overlay.currentRegion()
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

        // FV removes m2/g and its helper windows before the delayed action callback runs.
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        closeVisuals();

        final boolean result = !bounds.isEmpty();
        if (result) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (op == FvOperationHintOverlay.Mode.SCREENSHOT) {
                    ScreenshotController.captureBoundsForRegion(context, bounds);
                } else if (op == FvOperationHintOverlay.Mode.TEXT) {
                    ScreenshotController.captureBoundsForViewCandidate(context, bounds, view, text);
                } else {
                    ScreenshotController.captureBoundsForVisualCandidate(context, bounds, view);
                }
            }, FV_RELEASE_ACTION_DELAY_MS);
        }

        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_UP region=" + region
                + " target=" + (candidate != null) + " result=" + result + " focusHit="
                + Math.round(selectionX) + "," + Math.round(selectionY) + " op=" + op
                + " fvDelayMs=" + FV_RELEASE_ACTION_DELAY_MS);
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

        // FV FooViewService.D4() reads the active FloatIconView's current WindowManager x/y.
        // Use the owner icon's exact temporary-follow window bounds; keep the old transformer only
        // as a compatibility fallback for callers that do not provide an owner icon.
        RectF icon = ownerIcon == null ? null : ownerIcon.currentFvWindowBounds();
        if (icon == null || icon.isEmpty()) {
            icon = pointTransformer.iconBoundsForRaw(rawX, rawY);
        }
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
        DiagnosticLog.i(context, "FV_SELECT", "HOVER_ARM cache=true beforeProbe="
                + (probeOverlay == null));
    }

    private void closeVisuals() {
        if (probeOverlay != null) probeOverlay.close();
        probeOverlay = null;
        if (operationOverlay != null) operationOverlay.close();
        operationOverlay = null;
    }
}
