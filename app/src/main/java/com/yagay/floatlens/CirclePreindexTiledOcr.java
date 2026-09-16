package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds independent OCR passes for one frozen Circle frame.
 *
 * <p>There is intentionally no global merge. The full-frame result and overlapping tile results
 * keep their original line/group/character structures after being mapped into the same full-bitmap
 * coordinate plane. Gesture-time consensus later examines only the user's local target.</p>
 */
final class CirclePreindexTiledOcr {
    interface Callback {
        void onSuccess(CircleOcrIndex index);
        void onFailure(Throwable error);
    }

    private static final float TILE_FRACTION = 0.64f;
    private static final int MIN_FRAME_EDGE_FOR_TILES = 640;

    private static final class Pass {
        final Rect roi;
        final String name;
        final boolean full;

        Pass(Rect roi, String name, boolean full) {
            this.roi = new Rect(roi);
            this.name = name;
            this.full = full;
        }
    }

    private static final class Session {
        final Context app;
        final Bitmap source;
        final Callback callback;
        final List<Pass> passes;
        final ArrayList<CircleOcrIndex.Entry> entries = new ArrayList<>();
        int index;
        Throwable lastError;
        boolean finished;

        Session(Context app, Bitmap source, Callback callback) {
            this.app = app;
            this.source = source;
            this.callback = callback;
            this.passes = buildPasses(source.getWidth(), source.getHeight());
        }

        void start() {
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                    "start strategy=full_plus_overlap_tiles"
                            + " bitmap=" + source.getWidth() + "x" + source.getHeight()
                            + " passes=" + passes.size()
                            + " tileFraction=" + TILE_FRACTION
                            + " enginePolicy=follow_main_setting"
                            + " merge=none independentDocuments=true");
            runNext();
        }

        private void runNext() {
            if (finished) return;
            if (index >= passes.size()) {
                finish();
                return;
            }

            final Pass pass = passes.get(index++);
            final Bitmap input;
            try {
                input = pass.full ? source : Bitmap.createBitmap(source,
                        pass.roi.left, pass.roi.top, pass.roi.width(), pass.roi.height());
            } catch (Throwable t) {
                lastError = t;
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                        "prepare failed source=" + pass.name
                                + " roi=" + pass.roi.toShortString()
                                + " error=" + safe(t));
                runNext();
                return;
            }

            final long started = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                    "pass start source=" + pass.name
                            + " roi=" + pass.roi.toShortString()
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight());

            OcrEngine.recognizeDocument(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    try {
                        OcrDocument mapped = pass.full
                                ? normalizeFull(document, source.getWidth(), source.getHeight())
                                : mapTile(document, pass.roi, source.getWidth(),
                                        source.getHeight(), pass.name);
                        if (usable(mapped)) {
                            entries.add(new CircleOcrIndex.Entry(
                                    pass.name, pass.full, pass.roi, mapped));
                        }
                        DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                                "pass done source=" + pass.name
                                        + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                        + " lines=" + (mapped == null ? 0 : mapped.lines().size())
                                        + " engine=" + (document == null ? "none" : document.engine())
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));
                    } catch (Throwable t) {
                        lastError = t;
                        DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                                "map failed source=" + pass.name + " error=" + safe(t));
                    } finally {
                        if (!pass.full) recycle(input);
                    }
                    runNext();
                }

                @Override public void onFailure(Throwable error) {
                    lastError = error;
                    DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                            "pass failed source=" + pass.name
                                    + " error=" + safe(error)
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    if (!pass.full) recycle(input);
                    runNext();
                }
            });
        }

        private void finish() {
            if (finished) return;
            finished = true;
            CircleOcrIndex result = new CircleOcrIndex(entries);
            if (!result.isEmpty()) {
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                        "finish usable=true passes=" + result.passCount()
                                + " totalChars=" + result.totalChars()
                                + " merge=none independentDocuments=true");
                callback.onSuccess(result);
                return;
            }

            Throwable error = lastError == null
                    ? new IllegalStateException("tiled preindex OCR empty") : lastError;
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                    "finish usable=false passes=0 error=" + safe(error));
            callback.onFailure(error);
        }
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        new Session(context.getApplicationContext(), bitmap, callback).start();
    }

    private static List<Pass> buildPasses(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        ArrayList<Pass> out = new ArrayList<>();
        out.add(new Pass(new Rect(0, 0, w, h), "full", true));
        if (Math.min(w, h) < MIN_FRAME_EDGE_FOR_TILES) return List.copyOf(out);

        int tw = Math.max(1, Math.min(w, Math.round(w * TILE_FRACTION)));
        int th = Math.max(1, Math.min(h, Math.round(h * TILE_FRACTION)));
        if (tw >= w && th >= h) return List.copyOf(out);

        Set<String> seen = new HashSet<>();
        addPass(out, seen, new Rect(0, 0, tw, th), "tile-tl");
        addPass(out, seen, new Rect(w - tw, 0, w, th), "tile-tr");
        addPass(out, seen, new Rect(0, h - th, tw, h), "tile-bl");
        addPass(out, seen, new Rect(w - tw, h - th, w, h), "tile-br");
        return List.copyOf(out);
    }

    private static void addPass(List<Pass> out, Set<String> seen, Rect roi, String name) {
        if (roi == null || roi.isEmpty()) return;
        String key = roi.flattenToString();
        if (seen.add(key)) out.add(new Pass(roi, name, false));
    }

    private static OcrDocument normalizeFull(OcrDocument document, int width, int height) {
        if (document == null) return null;
        if (document.imageWidth() == width && document.imageHeight() == height) return document;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, document.imageWidth(), document.imageHeight()),
                new RectF(0f, 0f, Math.max(1, width), Math.max(1, height)));
        return mapper.mapDocument(document, false,
                Math.max(1, width), Math.max(1, height), "full-");
    }

    private static OcrDocument mapTile(OcrDocument document, Rect roi,
                                       int width, int height, String name) {
        if (document == null || roi == null || roi.isEmpty()) return null;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, document.imageWidth(), document.imageHeight()),
                new RectF(roi));
        return mapper.mapDocument(document, false,
                Math.max(1, width), Math.max(1, height), name + "-");
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isBitmapSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CirclePreindexTiledOcr() {}
}
