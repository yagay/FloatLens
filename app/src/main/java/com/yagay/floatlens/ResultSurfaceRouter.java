package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Thin construction facade over the single ResultController / ResultActivity pipeline. */
final class ResultSurfaceRouter {
    static boolean showScreenshot(Context c, Bitmap image, Rect sourceBounds) {
        return show(c, ResultSession.screenshot(image, sourceBounds), "screenshot");
    }

    static boolean showOcr(Context c, String text, List<String> blocks,
                           Bitmap image, Rect sourceBounds) {
        return show(c, ResultSession.ocr(text, blocks, image, sourceBounds), "ocr");
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect sourceBounds) {
        return show(c, ResultSession.viewText(text, image, sourceBounds), "view_text");
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect sourceBounds) {
        return show(c, ResultSession.viewImage(image, view, sourceBounds), "view_image");
    }

    static boolean showCapturedScreenshot(Context c, Bitmap image, Rect sourceBounds,
                                          FlSystemPanelController.CaptureState shadeState) {
        return showCaptured(c, ResultSession.screenshot(image, sourceBounds), shadeState,
                "captured_screenshot", "captured_screenshot_result");
    }

    static boolean showCapturedViewText(Context c, String text, Bitmap image, Rect sourceBounds,
                                        FlSystemPanelController.CaptureState shadeState) {
        return showCaptured(c, ResultSession.viewText(text, image, sourceBounds), shadeState,
                "captured_view_text", "captured_view_text_result");
    }

    static boolean showCapturedViewImage(Context c, Bitmap image, ViewNodeCandidate view,
                                         Rect sourceBounds,
                                         FlSystemPanelController.CaptureState shadeState) {
        return showCaptured(c, ResultSession.viewImage(image, view, sourceBounds), shadeState,
                "captured_view_image", "captured_view_image_result");
    }

    private static boolean show(Context c, ResultSession session, String kind) {
        boolean shown = ResultController.show(c, session);
        log(c, kind, shown, false);
        return shown;
    }

    private static boolean showCaptured(Context c, ResultSession session,
                                        FlSystemPanelController.CaptureState shadeState,
                                        String kind, String readyReason) {
        boolean shown = ResultController.showCaptured(c, session, shadeState, readyReason);
        log(c, kind, shown, true);
        return shown;
    }

    private static void log(Context c, String kind, boolean shown, boolean captured) {
        if (c != null) DiagnosticLog.i(c.getApplicationContext(), "RESULT_ROUTER",
                "single_activity kind=" + kind
                        + " captured=" + captured
                        + " shown=" + shown);
    }

    private ResultSurfaceRouter() {}
}
