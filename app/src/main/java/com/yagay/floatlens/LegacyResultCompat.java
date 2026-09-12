package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

/** Final Activity fallback when the floating screenshot host cannot attach. */
final class ScreenshotResultActivity {
    static boolean show(Context c, Bitmap image, Rect anchor) {
        return ResultActivity.showScreenshot(c, image, anchor);
    }
}
