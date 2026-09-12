package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * The Accessibility tree is cached before FloatLens adds its own probe/highlight windows. MOVE then
 * updates the red/yellow probe and performs only cached rectangle hit testing.
 *
 * Important: FV has two different operation-hint systems. The direct-selection hint that sits next
 * to the + probe belongs to m2/g.pointer_op_hint. It is NOT FooViewService.D4()'s 24dp owner-icon
 * hint. This class therefore binds FvPointerOperationHintOverlay to the probe window coordinates.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    // FV m2/g posts its region action 5ms after the selection/helper windows are removed.
    private static final long FV_RELEASE_ACTION_DELAY_MS = 5L;

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;

    private ViewHoverOverlay overlay;
    private FvProbePointOverlay probeOverlay;
    private FvPointerOperationHintOverlay pointerHintOverlay;
    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;

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
            // Starting a new pointer/selection interaction owns the UI from this point forward.
            // Any OCR callback from an older screenshot must not be allowed to open a stale popup.
            OcrEngine.invalidatePending(context, "new_float_selection");
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

        PointF shown = showProbeAt(transformed);
        hideOperationHint();

        selectionX = shown.x;
        selectionY = shown.y;

        if (overlay != null) overlay.update(selectionX, selectionY);
        return shown;
    }

    public void hideProbe() {
        if (probeOverlay != null) probeOverlay.hide();
        probeOverlay = null;
        if (pointerHintOverlay != null) pointerHintOverlay.close();
        pointerHintOverlay = null;
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
        updateOperationHint();

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
        updateOperationHint();
    }

    /**
     * FV m2/g.D() moves float_pen_view and pointer_op_hint together. Keep the sibling hint window
     * synchronized here on every point update, even while its internal icon is hidden.
     */
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
        final FvPointerOperationHintOverlay.Mode op = currentOperationMode();
        final Rect bounds;
        final ViewNodeCandidate view;
        final String text;

        if (op == FvPointerOperationHintOverlay.Mode.SCREENSHOT) {
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

        // FV m2/g.s() removes the selection window, float_pen_view and pointer_op_hint first.
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        closeVisuals();

        final boolean result = !bounds.isEmpty();
        if (result) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (op == FvPointerOperationHintOverlay.Mode.SCREENSHOT) {
                    ScreenshotController.captureBoundsForRegion(context, bounds);
                } else if (op == FvPointerOperationHintOverlay.Mode.TEXT) {
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

    private FvPointerOperationHintOverlay.Mode currentOperationMode() {
        if (overlay == null) return FvPointerOperationHintOverlay.Mode.SCREENSHOT;
        if (overlay.isRegionMode()) return FvPointerOperationHintOverlay.Mode.SCREENSHOT;

        ScreenCandidate candidate = overlay.currentCandidate();
        if (candidate == null) return FvPointerOperationHintOverlay.Mode.SCREENSHOT;
        if (candidate.type() == ScreenCandidate.Type.TEXT && candidate.hasText()) {
            return FvPointerOperationHintOverlay.Mode.TEXT;
        }
        if (candidate.type() == ScreenCandidate.Type.NON_TEXT) {
            return FvPointerOperationHintOverlay.Mode.IMAGE;
        }
        return FvPointerOperationHintOverlay.Mode.SCREENSHOT;
    }

    /**
     * FV m2/g.E() changes pointer_op_hint content. Position always comes from the + probe window,
     * never from FloatIconView, raw touch coordinates, or SelectionPointTransformer icon bounds.
     */
    private void updateOperationHint() {
        if (state != State.DIRECT || overlay == null || probeOverlay == null
                || !probeOverlay.isAttached()) {
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
        if (probeOverlay == null) probeOverlay = new FvProbePointOverlay(context);
    }

    private void ensurePointerHint() {
        if (pointerHintOverlay == null) {
            pointerHintOverlay = new FvPointerOperationHintOverlay(context);
        }
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
        if (pointerHintOverlay != null) pointerHintOverlay.close();
        pointerHintOverlay = null;
    }
}
