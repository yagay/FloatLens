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
    private final AtomicBoolean shadeResolved = new AtomicBoolean(false);
    private final AtomicBoolean shadeTransferred = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ScreenshotHideCoordinator.Lease iconLease;
    private CircleActiveBorderOverlay.CaptureLease borderLease;

    private CaptureTransaction(Context context, String reason) {
        app = context.getApplicationContext();
        this.reason = reason == null || reason.isBlank() ? "capture" : reason;
        shadeState = FlSystemPanelController.beginCapture(app, this.reason);
        DiagnosticLog.i(app, "CAPTURE_TX", "begin reason=" + this.reason);
    }

    static CaptureTransaction begin(Context context, String reason) {
        if (context == null) throw new IllegalArgumentException("context == null");
        return new CaptureTransaction(context, reason);
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

    /** ResultActivity now owns first-frame delivery for this shade state. */
    FlSystemPanelController.CaptureState transferShadeToResult() {
        shadeTransferred.set(true);
        DiagnosticLog.i(app, "CAPTURE_TX", "transfer shade reason=" + reason);
        return shadeState;
    }

    void resultReady(String readyReason) {
        if (shadeTransferred.get()) return;
        if (!shadeResolved.compareAndSet(false, true)) return;
        FlSystemPanelController.onResultReady(
                app, shadeState, readyReason == null ? reason : readyReason);
        DiagnosticLog.i(app, "CAPTURE_TX", "ready reason="
                + (readyReason == null ? reason : readyReason));
    }

    void overlayReady(String readyReason, FlSystemPanelController.PanelCallback callback) {
        if (shadeTransferred.get()) return;
        if (!shadeResolved.compareAndSet(false, true)) return;
        FlSystemPanelController.onOverlayReady(
                app, shadeState, readyReason == null ? reason : readyReason, callback);
        DiagnosticLog.i(app, "CAPTURE_TX", "overlay ready reason="
                + (readyReason == null ? reason : readyReason));
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
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

        // A failed/aborted capture must never strand a system-panel state.
        if (!shadeTransferred.get() && shadeResolved.compareAndSet(false, true)) {
            FlSystemPanelController.onResultReady(app, shadeState, reason + "_closed");
        }
        DiagnosticLog.i(app, "CAPTURE_TX", "close reason=" + reason
                + " transferred=" + shadeTransferred.get());
    }
}
