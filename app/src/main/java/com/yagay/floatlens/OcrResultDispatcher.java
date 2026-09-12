package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes OCR output without coupling OcrEngine to any concrete result UI.
 *
 * Callers that want inline OCR register the exact source Bitmap. The registration is one-shot.
 * Unclaimed results go through ResultSurfaceRouter to the normal FloatLens result surface.
 */
final class OcrResultDispatcher {
    interface Sink {
        void onResult(String text, List<String> blocks);
    }

    private static final Object LOCK = new Object();
    private static final Map<Bitmap, Sink> INLINE = new IdentityHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void register(Bitmap image, Sink sink) {
        if (image == null) return;
        synchronized (LOCK) {
            if (sink == null) INLINE.remove(image);
            else INLINE.put(image, sink);
        }
    }

    static void cancel(Bitmap image) {
        if (image == null) return;
        synchronized (LOCK) { INLINE.remove(image); }
    }

    static boolean deliver(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        if (c == null) return false;
        Context app = c.getApplicationContext();
        String value = text == null ? "" : text;
        List<String> safeBlocks = blocks == null ? List.of() : new ArrayList<>(blocks);
        Sink sink;
        synchronized (LOCK) { sink = image == null ? null : INLINE.remove(image); }

        if (sink != null) {
            MAIN.post(() -> {
                try {
                    sink.onResult(value, safeBlocks);
                    DiagnosticLog.i(app, "OCR_DISPATCH", "inline chars=" + value.length()
                            + " blocks=" + safeBlocks.size());
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "OCR_DISPATCH", "inline failed=" + t);
                    ResultSurfaceRouter.showOcr(app, value, safeBlocks, image, anchor);
                }
            });
            return true;
        }

        MAIN.post(() -> {
            boolean shown = ResultSurfaceRouter.showOcr(app, value, safeBlocks, image, anchor);
            DiagnosticLog.i(app, "OCR_DISPATCH", "default chars=" + value.length()
                    + " blocks=" + safeBlocks.size() + " shown=" + shown);
        });
        return true;
    }

    private OcrResultDispatcher() {}
}
