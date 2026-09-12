package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Legacy API preserved for callers; all implementation now lives in ResultSurfaceRouter. */
public final class ResultOverlay {
    public static void show(Context c, String text, List<String> blocks, Bitmap image) {
        show(c, text, blocks, image, null);
    }

    public static void show(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        ResultSurfaceRouter.showOcr(c, text, blocks, image, anchor);
    }

    public static void showVisual(Context c, Bitmap image, ViewNodeCandidate view) {
        Rect anchor = view == null ? null : view.bounds();
        showVisual(c, image, view, anchor);
    }

    public static void showVisual(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        ResultSurfaceRouter.showViewImage(c, image, view, anchor);
    }

    private ResultOverlay() {}
}
