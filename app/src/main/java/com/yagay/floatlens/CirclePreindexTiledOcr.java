package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Builds the frozen Circle OCR index from one full-frame pass.
 *
 * <p>The preindex is deliberately simple: one screenshot, one document OCR request, one geometry
 * mapping. It is used for ordinary text and coarse location only. Small image text/logos are
 * verified later from a tight local crop, so overlapping full-screen tiles cannot vote against
 * each other or make the result change between otherwise identical gestures.</p>
 */
final class CirclePreindexTiledOcr {
    interface Callback {
        void onSuccess(CircleOcrIndex index);
        void onFailure(Throwable error);
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        recognize(context, bitmap, () -> false, callback);
    }

    static void recognize(Context context, Bitmap bitmap,
                          BooleanSupplier cancelled, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();
        if (isCancelled(cancelled)) {
            callback.onFailure(new CancellationException("Circle preindex cancelled before full pass"));
            return;
        }

        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final Rect coverage = new Rect(0, 0, width, height);
        final long started = android.os.SystemClock.uptimeMillis();

        DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                "start strategy=single_full_frame"
                        + " bitmap=" + width + "x" + height
                        + " passes=1"
                        + " tiles=false merge=false"
                        + " enginePolicy=follow_main_setting"
                        + " workspaceCancellation=true");

        OcrEngine.recognizeDocument(app, bitmap, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (isCancelled(cancelled)) {
                    DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                            "cancel stage=after_full elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    callback.onFailure(new CancellationException(
                            "Circle preindex cancelled after full pass"));
                    return;
                }
                try {
                    OcrDocument normalized = normalizeFull(document, width, height);
                    if (!usable(normalized)) {
                        callback.onFailure(new IllegalStateException("full-frame preindex OCR empty"));
                        return;
                    }
                    CircleOcrIndex result = new CircleOcrIndex(List.of(
                            new CircleOcrIndex.Entry("full", true, coverage, normalized)));
                    DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                            "finish usable=true passes=1"
                                    + " chars=" + normalized.chars().size()
                                    + " lines=" + normalized.lines().size()
                                    + " engine=" + normalized.engine()
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    callback.onSuccess(result);
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                            "finish usable=false error=" + safe(t));
                    callback.onFailure(t);
                }
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                        "failed error=" + safe(error)
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onFailure(error == null
                        ? new IllegalStateException("full-frame preindex OCR failed") : error);
            }
        });
    }

    private static OcrDocument normalizeFull(OcrDocument document, int width, int height) {
        if (document == null) return null;
        if (document.imageWidth() == width && document.imageHeight() == height) return document;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, Math.max(1, document.imageWidth()),
                        Math.max(1, document.imageHeight())),
                new RectF(0f, 0f, Math.max(1, width), Math.max(1, height)));
        return mapper.mapDocument(document, false,
                Math.max(1, width), Math.max(1, height), "full-");
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isBitmapSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        if (cancelled == null) return false;
        try { return cancelled.getAsBoolean(); }
        catch (Throwable ignored) { return true; }
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CirclePreindexTiledOcr() {}
}
