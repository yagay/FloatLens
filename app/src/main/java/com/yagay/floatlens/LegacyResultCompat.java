package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Temporary source-compatibility names while old callers are migrated to shared result components. */
final class ResultTextActivity {
    interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    static boolean show(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        return OcrResultDispatcher.deliver(c, text, blocks, image, anchor);
    }

    static void captureNextForImage(Bitmap image, InlineResultSink sink) {
        OcrResultDispatcher.register(image, sink == null ? null : sink::onResult);
    }

    static void clearInlineForImage(Bitmap image) {
        OcrResultDispatcher.cancel(image);
    }
}

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
