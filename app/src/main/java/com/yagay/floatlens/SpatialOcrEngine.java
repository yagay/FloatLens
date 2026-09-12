package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/**
 * Coordinate result facade for Circle Select.
 *
 * Recognition itself is owned by OcrEngine so Circle Select and normal OCR always share the same
 * engine selection, language settings, preprocessing, fallback and scoring policy.
 */
public final class SpatialOcrEngine {
    public interface Callback {
        void onSuccess(List<Word> words);
        void onFailure(Throwable error);
    }

    public static final class Word {
        private final String text;
        private final Rect bounds;
        private final int line;
        private final int group;
        private final int order;

        Word(String text, Rect bounds, int line, int group, int order) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.line = line;
            this.group = group;
            this.order = order;
        }

        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public int line() { return line; }
        public int group() { return group; }
        public int order() { return order; }
    }

    public static void recognize(Context c, Bitmap source, Callback callback) {
        OcrEngine.recognizeSpatial(c, source, callback);
    }

    private SpatialOcrEngine() {}
}
