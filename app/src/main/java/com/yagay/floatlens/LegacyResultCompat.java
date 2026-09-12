package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Temporary compatibility shims while callers migrate to ResultActivity. */
final class ResultTextActivity {
    interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    static boolean show(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        return ResultActivity.showOcr(c, text, blocks, image, anchor);
    }

    static void captureNextForImage(Bitmap image, InlineResultSink sink) {
        ResultActivity.captureNextForImage(image,
                sink == null ? null : sink::onResult);
    }

    static void clearInlineForImage(Bitmap image) {
        ResultActivity.clearInlineForImage(image);
    }
}

final class ScreenshotResultActivity {
    static boolean show(Context c, Bitmap image, Rect anchor) {
        return ResultActivity.showScreenshot(c, image, anchor);
    }
}

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
