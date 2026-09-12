package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.List;

/**
 * Source-compatibility adapter for EditableRegionOverlay.
 *
 * Contains no result/window implementation. New code must call ResultSurfaceRouter directly.
 */
@Deprecated
final class ResultOverlay {
    static void show(Context c, String text, List<String> blocks, Bitmap image) {
        ResultSurfaceRouter.showOcr(c, text, blocks, image, null);
    }

    private ResultOverlay() {}
}
