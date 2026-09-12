package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Temporary source-compatibility names. Real routing lives in shared result components. */
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

/** Used only as the final Activity fallback when the floating screenshot host cannot attach. */
final class ScreenshotResultActivity {
    static boolean show(Context c, Bitmap image, Rect anchor) {
        return ResultActivity.showScreenshot(c, image, anchor);
    }
}

final class ViewContentActivity {
    static boolean show(Context c, String text, Bitmap image, Rect anchor) {
        return ResultSurfaceRouter.showViewText(c, text, image, anchor);
    }
}

final class ViewImageResultActivity {
    static boolean show(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        return ResultSurfaceRouter.showViewImage(c, image, view, anchor);
    }
}
