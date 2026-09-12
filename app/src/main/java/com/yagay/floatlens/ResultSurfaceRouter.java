package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/**
 * Single routing policy for screenshot, View and OCR result surfaces.
 *
 * A result-surface failure must never be reported as a screenshot/crop failure. Floating windows are
 * best-effort; every entry point isolates their runtime exceptions and falls back to ResultActivity.
 */
final class ResultSurfaceRouter {
    static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        Context app = c.getApplicationContext();
        if (tryFloating(app, "screenshot", () -> FloatingResultWindow.showScreenshot(app, image, anchor))) {
            return true;
        }
        return ResultActivity.showScreenshot(app, image, anchor);
    }

    static boolean showOcr(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        Context app = c.getApplicationContext();
        if (tryFloating(app, "ocr", () -> FloatingResultWindow.showOcr(app, text, blocks, image, anchor))) {
            return true;
        }
        return ResultActivity.showOcr(app, text, blocks, image, anchor);
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        Context app = c.getApplicationContext();
        if (tryFloating(app, "view_text", () -> FloatingResultWindow.showViewText(app, text, image, anchor))) {
            return true;
        }
        return ResultActivity.showViewText(app, text, image, anchor);
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        Context app = c.getApplicationContext();
        if (tryFloating(app, "view_image", () -> FloatingResultWindow.showViewImage(app, image, view, anchor))) {
            return true;
        }
        return ResultActivity.showViewImage(app, image, view, anchor);
    }

    /** Captured screenshot: show frozen overlay first, clean shade in background, Activity only as fallback. */
    static boolean showCapturedScreenshot(Context c, Bitmap image, Rect anchor,
                                          FvSystemPanelController.CaptureState shadeState) {
        Context app = c.getApplicationContext();
        if (tryFloating(app, "captured_screenshot",
                () -> FloatingResultWindow.showScreenshot(app, image, anchor))) {
            boolean captureExpanded = shadeState != null && shadeState.expandedAtCapture();
            OverlayShadeCoordinator.cleanup(app, captureExpanded,
                    "screenshot_result", collapsed -> DiagnosticLog.i(app, "SCREENSHOT_RESULT",
                            "background shade cleanup collapsed=" + collapsed));
            return true;
        }

        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "screenshot_activity_fallback");
        boolean activity = ResultActivity.showScreenshot(app, image, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "fallback kind=captured_screenshot activity=" + activity);
        if (activity) return true;
        ResultReadyCoordinator.cancel(ticket, app, "screenshot_activity_start_failed");
        return false;
    }

    /**
     * View capture path: bootstrap the floating result above SystemUI and let it own shade cleanup.
     * Only if no floating host can attach do we arm the legacy Activity-ready coordinator.
     */
    static boolean showCapturedViewText(Context c, String text, Bitmap image, Rect anchor,
                                        FvSystemPanelController.CaptureState shadeState) {
        Context app = c.getApplicationContext();
        boolean captureExpanded = shadeState != null && shadeState.expandedAtCapture();
        if (tryFloating(app, "captured_view_text",
                () -> FloatingResultWindow.showViewText(app, text, image, anchor, captureExpanded))) {
            return true;
        }

        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "view_text_activity_fallback");
        boolean activity = ResultActivity.showViewText(app, text, image, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "fallback kind=captured_view_text activity=" + activity);
        if (activity) return true;
        ResultReadyCoordinator.cancel(ticket, app, "view_text_activity_start_failed");
        return false;
    }

    static boolean showCapturedViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor,
                                         FvSystemPanelController.CaptureState shadeState) {
        Context app = c.getApplicationContext();
        boolean captureExpanded = shadeState != null && shadeState.expandedAtCapture();
        if (tryFloating(app, "captured_view_image",
                () -> FloatingResultWindow.showViewImage(app, image, view, anchor, captureExpanded))) {
            return true;
        }

        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "view_image_activity_fallback");
        boolean activity = ResultActivity.showViewImage(app, image, view, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "fallback kind=captured_view_image activity=" + activity);
        if (activity) return true;
        ResultReadyCoordinator.cancel(ticket, app, "view_image_activity_start_failed");
        return false;
    }

    private interface FloatingCall { boolean run(); }

    private static boolean tryFloating(Context app, String kind, FloatingCall call) {
        try {
            boolean shown = call.run();
            DiagnosticLog.i(app, "RESULT_ROUTER", "floating kind=" + kind + " shown=" + shown);
            return shown;
        } catch (Throwable t) {
            // A partially constructed/attached result must not survive a failed show attempt.
            try { FloatingResultWindow.dismissActive("show_failed_" + kind); } catch (Throwable ignored) {}
            DiagnosticLog.i(app, "RESULT_ROUTER", "floating exception kind=" + kind
                    + " error=" + ScreenCaptureBackend.safeMessage(t));
            return false;
        }
    }

    private ResultSurfaceRouter() {}
}
