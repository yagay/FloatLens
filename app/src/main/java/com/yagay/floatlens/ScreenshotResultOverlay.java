package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

/** Compatibility entry point for the unified floating result window. */
public final class ScreenshotResultOverlay {
    public static boolean show(Context c, Bitmap image, Rect anchor) {
        return FloatingResultWindow.showScreenshot(c, image, anchor);
    }

    public static void dismissActive(String reason) {
        FloatingResultWindow.dismissActive(reason);
    }

    private ScreenshotResultOverlay() {}
}
