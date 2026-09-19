package com.yagay.floatlens;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one capture's system-panel lifecycle and optional temporary overlay-hide leases.
 *
 * <p>Every capture path must resolve its shade state exactly once. ResultActivity flows transfer
 * that responsibility to ResultReadyCoordinator; immediate/overlay/failure paths resolve it here.
 * close() is idempotent and is a final safety net for exceptional exits.</p>
 */
final class CaptureTransaction implements AutoCloseable {
    private final Context app;
    private final String reason;
    private final FlSystemPanelController.CaptureState shadeState;
    private final boolean ownsShade;
    private final AtomicBoolean shadeResolved = new AtomicBoolean(false);
    private final AtomicBoolean shadeTransferred = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean visualsReleased = new AtomicBoolean(false);

    private ScreenshotHideCoordinator.Lease iconLease;
    private CircleActiveBorderOverlay.CaptureLease borderLease;

    private CaptureTransaction(Context context, String reason, boolean ownsShade) {
        app = context.getApplicationContext();
        this.reason = reason == null || reason.isBlank() ? "capture" : reason;
        this.ownsShade = ownsShade;
        shadeState = ownsShade ? FlSystemPanelController.beginCapture(app, this.reason) : null;
        DiagnosticLog.i(app, "CAPTURE_TX", "begin reason=" + this.reason
                + " ownsShade=" + ownsShade);
    }

    static CaptureTransaction begin(Context context, String reason) {
        if (context == null) throw new IllegalArgumentException("context == null");
        return new CaptureTransaction(context, reason, true);
    }

    static CaptureTransaction visual(Context context, String reason) {
        if (context == null) throw new IllegalArgumentException("context == null");
        return new CaptureTransaction(context, reason, false);
    }

    synchronized CaptureTransaction hideFloatingIcon(String leaseReason) {
        if (iconLease == null) {
            iconLease = ScreenshotHideCoordinator.acquire(
                    app, leaseReason == null ? reason : leaseReason);
        }
        return this;
    }

    synchronized CaptureTransaction hideCircleBorder(String leaseReason) {
        if (borderLease == null) {
            borderLease = CircleActiveBorderOverlay.acquireCaptureHidden(
                    app, leaseReason == null ? reason : leaseReason);
        }
        return this;
    }

    FlSystemPanelController.CaptureState shadeState() {
        return shadeState;
    }

    boolean circleBorderHidden() {
        synchronized (this) {
            return borderLease != null && borderLease.requiresSettle();
        }
    }

    /** ResultActivity now owns first-frame delivery for this shade state. */
    FlSystemPanelController.CaptureState transferShadeToResult() {
        if (!ownsShade || shadeState == null) return null;
        shadeTransferred.set(true);
        DiagnosticLog.i(app, "CAPTURE_TX", "transfer shade reason=" + reason);
        return shadeState;
    }

    void resultReady(String readyReason) {
        if (!ownsShade || shadeState == null || shadeTransferred.get()) return;
        if (!shadeResolved.compareAndSet(false, true)) return;
        FlSystemPanelController.onResultReady(
                app, shadeState, readyReason == null ? reason : readyReason);
        DiagnosticLog.i(app, "CAPTURE_TX", "ready reason="
                + (readyReason == null ? reason : readyReason));
    }

    void overlayReady(String readyReason, FlSystemPanelController.PanelCallback callback) {
        if (!ownsShade || shadeState == null || shadeTransferred.get()) {
            if (callback != null) callback.onComplete(true);
            return;
        }
        if (!shadeResolved.compareAndSet(false, true)) return;
        FlSystemPanelController.onOverlayReady(
                app, shadeState, readyReason == null ? reason : readyReason, callback);
        DiagnosticLog.i(app, "CAPTURE_TX", "overlay ready reason="
                + (readyReason == null ? reason : readyReason));
    }

    void releaseVisuals() {
        if (!visualsReleased.compareAndSet(false, true)) return;
        ScreenshotHideCoordinator.Lease icon;
        CircleActiveBorderOverlay.CaptureLease border;
        synchronized (this) {
            icon = iconLease;
            iconLease = null;
            border = borderLease;
            borderLease = null;
        }
        if (icon != null) icon.release(app);
        if (border != null) border.release(app);
        DiagnosticLog.i(app, "CAPTURE_TX", "visuals released reason=" + reason);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        releaseVisuals();

        // A failed/aborted capture must never strand a system-panel state.
        if (ownsShade && shadeState != null
                && !shadeTransferred.get() && shadeResolved.compareAndSet(false, true)) {
            FlSystemPanelController.onResultReady(app, shadeState, reason + "_closed");
        }
        DiagnosticLog.i(app, "CAPTURE_TX", "close reason=" + reason
                + " ownsShade=" + ownsShade
                + " transferred=" + shadeTransferred.get());
    }
}
