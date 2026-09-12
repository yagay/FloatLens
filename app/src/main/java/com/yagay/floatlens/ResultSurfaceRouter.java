package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/**
 * Single routing policy for result surfaces.
 *
 * Image-only screenshot results can safely live in an accessibility/application overlay. Results
 * that require Android's native text Editor (selection handles, magnifier and ActionMode) are
 * deliberately hosted by ResultActivity. Android/OxygenOS does not reliably provide the native
 * Editor interaction stack inside TYPE_APPLICATION_OVERLAY, even when that window is focusable.
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
        boolean shown = ResultActivity.showOcr(app, text, blocks, image, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "native_activity kind=ocr shown=" + shown);
        return shown;
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        Context app = c.getApplicationContext();
        boolean shown = ResultActivity.showViewText(app, text, image, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "native_activity kind=view_text shown=" + shown);
        return shown;
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        Context app = c.getApplicationContext();
        boolean shown = ResultActivity.showViewImage(app, image, view, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "native_activity kind=view_image shown=" + shown);
        return shown;
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
     * View text always uses the Activity window because it requires the framework's real Editor
     * selection stack. The result-ready coordinator preserves the existing SystemUI/shade behavior.
     */
    static boolean showCapturedViewText(Context c, String text, Bitmap image, Rect anchor,
                                        FvSystemPanelController.CaptureState shadeState) {
        Context app = c.getApplicationContext();
        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "view_text_native_activity");
        boolean activity = ResultActivity.showViewText(app, text, image, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "native_activity kind=captured_view_text shown=" + activity);
        if (activity) return true;
        ResultReadyCoordinator.cancel(ticket, app, "view_text_activity_start_failed");
        return false;
    }

    /** View image/meta also uses the Activity host so any displayed metadata remains selectable. */
    static boolean showCapturedViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor,
                                         FvSystemPanelController.CaptureState shadeState) {
        Context app = c.getApplicationContext();
        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "view_image_native_activity");
        boolean activity = ResultActivity.showViewImage(app, image, view, anchor);
        DiagnosticLog.i(app, "RESULT_ROUTER", "native_activity kind=captured_view_image shown=" + activity);
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
            try { FloatingResultWindow.dismissActive("show_failed_" + kind); } catch (Throwable ignored) {}
            DiagnosticLog.i(app, "RESULT_ROUTER", "floating exception kind=" + kind
                    + " error=" + ScreenCaptureBackend.safeMessage(t));
            return false;
        }
    }

    private ResultSurfaceRouter() {}
}
