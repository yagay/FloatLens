package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Thin compatibility facade over the single ResultController / ResultActivity pipeline. */
final class ResultSurfaceRouter {
    static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        boolean shown = ResultController.show(c, ResultSession.screenshot(image, anchor));
        log(c, "screenshot", shown); return shown;
    }
    static boolean showOcr(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        boolean shown = ResultController.show(c, ResultSession.ocr(text, blocks, image, anchor));
        log(c, "ocr", shown); return shown;
    }
    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        boolean shown = ResultController.show(c, ResultSession.viewText(text, image, anchor));
        log(c, "view_text", shown); return shown;
    }
    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        boolean shown = ResultController.show(c, ResultSession.viewImage(image, view, anchor));
        log(c, "view_image", shown); return shown;
    }
    static boolean showCapturedScreenshot(Context c, Bitmap image, Rect anchor,
                                          FlSystemPanelController.CaptureState shadeState) {
        boolean shown = ResultController.showCaptured(c, ResultSession.screenshot(image, anchor), shadeState,
                "captured_screenshot_result");
        log(c, "captured_screenshot", shown); return shown;
    }
    static boolean showCapturedViewText(Context c, String text, Bitmap image, Rect anchor,
                                        FlSystemPanelController.CaptureState shadeState) {
        boolean shown = ResultController.showCaptured(c, ResultSession.viewText(text, image, anchor), shadeState,
                "captured_view_text_result");
        log(c, "captured_view_text", shown); return shown;
    }
    static boolean showCapturedViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor,
                                         FlSystemPanelController.CaptureState shadeState) {
        boolean shown = ResultController.showCaptured(c, ResultSession.viewImage(image, view, anchor), shadeState,
                "captured_view_image_result");
        log(c, "captured_view_image", shown); return shown;
    }
    private static void log(Context c, String kind, boolean shown) {
        if (c != null) DiagnosticLog.i(c.getApplicationContext(), "RESULT_ROUTER",
                "single_activity kind=" + kind + " shown=" + shown);
    }
    private ResultSurfaceRouter() {}
}
