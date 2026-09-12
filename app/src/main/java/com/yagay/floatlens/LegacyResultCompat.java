package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

/** Temporary source-compatibility names while old View callers are migrated. */

/** Final Activity fallback when the floating screenshot host cannot attach. */
final class ScreenshotResultActivity {
    static boolean show(Context c, Bitmap image, Rect anchor) {
        return ResultActivity.showScreenshot(c, image, anchor);
    }
}

/**
 * These two still start ResultActivity because ScreenshotController's current View capture path
 * arms ResultReadyCoordinator before calling them. Once that controller is migrated together with
 * shade cleanup, these aliases can be removed entirely.
 */
final class ViewContentActivity {
    static boolean show(Context c, String text, Bitmap image, Rect anchor) {
        return ResultActivity.showViewText(c, text, image, anchor);
    }
}

final class ViewImageResultActivity {
    static boolean show(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        return ResultActivity.showViewImage(c, image, view, anchor);
    }
}
