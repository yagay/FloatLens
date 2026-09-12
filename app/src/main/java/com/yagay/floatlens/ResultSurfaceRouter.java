package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Single routing policy for screenshot, View and OCR result surfaces. */
final class ResultSurfaceRouter {
    static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        if (FloatingResultWindow.showScreenshot(c, image, anchor)) return true;
        return ResultActivity.showScreenshot(c, image, anchor);
    }

    static boolean showOcr(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        if (FloatingResultWindow.showOcr(c, text, blocks, image, anchor)) return true;
        return ResultActivity.showOcr(c, text, blocks, image, anchor);
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        if (FloatingResultWindow.showViewText(c, text, image, anchor)) return true;
        return ResultActivity.showViewText(c, text, image, anchor);
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        if (FloatingResultWindow.showViewImage(c, image, view, anchor)) return true;
        return ResultActivity.showViewImage(c, image, view, anchor);
    }

    /** Captured screenshot: show frozen overlay first, clean shade in background, Activity only as fallback. */
    static boolean showCapturedScreenshot(Context c, Bitmap image, Rect anchor,
                                          FvSystemPanelController.CaptureState shadeState) {
        Context app = c.getApplicationContext();
        if (FloatingResultWindow.showScreenshot(app, image, anchor)) {
            boolean captureExpanded = shadeState != null && shadeState.expandedAtCapture();
            OverlayShadeCoordinator.cleanup(app, captureExpanded,
                    "screenshot_result", collapsed -> DiagnosticLog.i(app, "SCREENSHOT_RESULT",
                            "background shade cleanup collapsed=" + collapsed));
            return true;
        }

        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "screenshot_activity_fallback");
        if (ResultActivity.showScreenshot(app, image, anchor)) return true;
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
        if (FloatingResultWindow.showViewText(app, text, image, anchor, captureExpanded)) return true;

        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "view_text_activity_fallback");
        if (ResultActivity.showViewText(app, text, image, anchor)) return true;
        ResultReadyCoordinator.cancel(ticket, app, "view_text_activity_start_failed");
        return false;
    }

    static boolean showCapturedViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor,
                                         FvSystemPanelController.CaptureState shadeState) {
        Context app = c.getApplicationContext();
        boolean captureExpanded = shadeState != null && shadeState.expandedAtCapture();
        if (FloatingResultWindow.showViewImage(app, image, view, anchor, captureExpanded)) return true;

        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "view_image_activity_fallback");
        if (ResultActivity.showViewImage(app, image, view, anchor)) return true;
        ResultReadyCoordinator.cancel(ticket, app, "view_image_activity_start_failed");
        return false;
    }

    private ResultSurfaceRouter() {}
}
