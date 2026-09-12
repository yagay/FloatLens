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

    private ResultSurfaceRouter() {}
}
