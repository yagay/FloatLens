package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screenshot-only text resolver for the Google-style Circle workspace.
 *
 * <p>The frozen screenshot is the only source of truth. A fast full-frame ML Kit pass is started in
 * the background only as a cache. Every user text gesture can immediately fall back to a tight crop
 * from the same frozen screenshot and run Circle's direct ML Kit recognizer on that crop. Therefore
 * interactive selection never waits for PP-OCR or for a full-frame pre-index to finish, and a miss
 * against cached character rectangles is retried from pixels instead of returning NONE.</p>
 */
final class GoogleCircleTextResolver {
    enum Source { VIEW, VIEW_OCR, IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        /** SCREEN-space OCR document used by the selection surface. */
        final OcrDocument document;
        /** Gesture-scoped subset used only to initialize selection start/end. */
        final OcrDocument initialSelectionDocument;
        final Rect gestureScreenBounds;
        final Rect ocrBitmapRoi;
        final Throwable error;
        final boolean localFallback;

        Result(Source source, OcrDocument document, OcrDocument initialSelectionDocument,
               Rect gestureScreenBounds, Rect ocrBitmapRoi, Throwable error,
               boolean localFallback) {
            this.source = source == null ? Source.NONE : source;
            this.document = document;
            this.initialSelectionDocument = initialSelectionDocument;
            this.gestureScreenBounds = gestureScreenBounds == null
                    ? new Rect() : new Rect(gestureScreenBounds);
            this.ocrBitmapRoi = ocrBitmapRoi == null ? new Rect() : new Rect(ocrBitmapRoi);
            this.error = error;
            this.localFallback = localFallback;
        }
    }

    private static final class PreloadState {
        final long generation;
        final Context app;
        final WeakReference<GoogleCircleCapture.Frame> frameRef;

        OcrDocument screenDocument;
        Throwable ocrError;
        boolean ocrDone;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }
    }

    private static final ExecutorService INDEX_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-screenshot-ocr");
        t.setDaemon(true);
        return t;
    });

    private static final Object INDEX_LOCK = new Object();
    private static final float CACHED_OCR_TAP_TOLERANCE_DP = 10f;
    private static final float RANGE_CORRIDOR_DP = 10f;
    private static final float LOCAL_TAP_HALF_WIDTH_DP = 92f;
    private static final float LOCAL_TAP_HALF_HEIGHT_DP = 42f;
    private static final float LOCAL_RANGE_PAD_X_DP = 18f;
    private static final float LOCAL_RANGE_PAD_Y_DP = 16f;
    private static final float LOCAL_MIN_WIDTH_DP = 96f;
    private static final float LOCAL_MIN_HEIGHT_DP = 44f;

    private static long indexGeneration;
    private static PreloadState currentIndex;

    static void preload(Context c, GoogleCircleCapture.Frame frame) {
        if (c == null || frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) return;
        Context app = c.getApplicationContext();
        PreloadState state;
        synchronized (INDEX_LOCK) {
            if (sameFrame(currentIndex, frame)) return;
            if (currentIndex != null) clearStateLocked(currentIndex);
            state = new PreloadState(++indexGeneration, app, frame);
            currentIndex = state;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "G_CIRCLE_TEXT_INDEX", "start generation=" + state.generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " strategy=frozen_screenshot_fast_mlkit_plus_local_crop"
                + " textOwner=IMAGE_OCR_ONLY"
                + " preindex=optional_fast_mlkit"
                + " localOcr=direct_mlkit_nonblocking"
                + " viewText=false ppocrPreindex=false"
                + " geometry=shared_matrix_transform coordinateSpace=SCREEN");

        INDEX_IO.execute(() -> prepareFullScreenshotOcr(state, frame, started));
    }

    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (c == null || frame == null || gesture == null || callback == null) return;
        Context app = c.getApplicationContext();
        preload(app, frame);

        PreloadState state;
        OcrDocument cached;
        Throwable cachedError;
        synchronized (INDEX_LOCK) {
            state = sameFrame(currentIndex, frame) ? currentIndex : null;
            if (state == null) {
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreenBounds(frame, gesture), new Rect(),
                        new IllegalStateException("screenshot OCR state unavailable"), true));
                return;
            }
            cached = state.screenDocument;
            cachedError = state.ocrError;
        }

        if (usable(cached)) {
            OcrDocument initial = selectGesture(state.app, frame, gesture, cached);
            if (usable(initial)) {
                Rect fullBitmap = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "cached screenshot hit gesture="
                        + gesture.kind
                        + " documentChars=" + cached.chars().size()
                        + " initialChars=" + initial.chars().size()
                        + " text=" + summarize(initial.fullText())
                        + " engine=" + cached.engine()
                        + " localFallback=false coordinateSpace=SCREEN");
                callback.onResolved(new Result(Source.IMAGE_OCR, cached, initial,
                        gestureScreenBounds(frame, gesture), fullBitmap, null, false));
                return;
            }
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "cached screenshot miss gesture="
                    + gesture.kind + " -> local screenshot OCR"
                    + " documentChars=" + cached.chars().size()
                    + " engine=" + cached.engine());
        } else {
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "full screenshot cache not ready gesture="
                    + gesture.kind + " -> local screenshot OCR"
                    + (cachedError == null ? "" : " cachedError=" + safe(cachedError)));
        }

        resolveLocalScreenshot(state, frame, gesture, callback);
    }

    static void release(Context c, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = c == null ? null : c.getApplicationContext();
        PreloadState released;
        int chars;
        int lines;
        synchronized (INDEX_LOCK) {
            if (!sameFrame(currentIndex, frame)) return;
            released = currentIndex;
            chars = released.screenDocument == null ? 0 : released.screenDocument.chars().size();
            lines = released.screenDocument == null ? 0 : released.screenDocument.lines().size();
            currentIndex = null;
            indexGeneration++;
            clearStateLocked(released);
        }
        Context logContext = app == null ? released.app : app;
        DiagnosticLog.i(logContext, "G_CIRCLE_TEXT_INDEX", "release generation="
                + released.generation
                + " reason=" + (reason == null ? "unknown" : reason)
                + " chars=" + chars + " lines=" + lines);
    }

    private static void clearStateLocked(PreloadState state) {
        if (state == null) return;
        state.screenDocument = null;
        state.ocrError = null;
        state.ocrDone = false;
    }

    private static void prepareFullScreenshotOcr(PreloadState state,
                                                 GoogleCircleCapture.Frame frame,
                                                 long started) {
        if (!isCurrent(state, frame)) return;
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) return;

        final Bitmap copy;
        try {
            copy = source.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("full screenshot OCR copy failed");
        } catch (Throwable t) {
            finishFullScreenshotOcr(state, frame, null, t, started);
            return;
        }

        if (!isCurrent(state, frame)) {
            recycle(copy);
            return;
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "full screenshot mlkit begin generation="
                + state.generation + " bitmap=" + copy.getWidth() + "x" + copy.getHeight());
        CircleStableOcr.recognizeMlKit(state.app, copy, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument bitmapDocument) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument screenDocument = bitmapDocument == null
                            ? null : frame.transform.documentBitmapToScreen(bitmapDocument);
                    finishFullScreenshotOcr(state, frame, screenDocument, null, started);
                } finally {
                    recycle(copy);
                }
            }

            @Override public void onFailure(Throwable error) {
                try {
                    if (isCurrent(state, frame)) {
                        finishFullScreenshotOcr(state, frame, null, error, started);
                    }
                } finally {
                    recycle(copy);
                }
            }
        });
    }

    private static void finishFullScreenshotOcr(PreloadState state,
                                                GoogleCircleCapture.Frame frame,
                                                OcrDocument document,
                                                Throwable error,
                                                long started) {
        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return;
            state.screenDocument = document;
            state.ocrError = error;
            state.ocrDone = true;
        }
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "full screenshot mlkit ready generation="
                + state.generation
                + " usable=" + usable(document)
                + " chars=" + (document == null ? 0 : document.chars().size())
                + " lines=" + (document == null ? 0 : document.lines().size())
                + " engine=" + (document == null ? "none" : document.engine())
                + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                + (error == null ? "" : " error=" + safe(error)));
    }

    private static void resolveLocalScreenshot(PreloadState state,
                                               GoogleCircleCapture.Frame frame,
                                               GoogleCircleSelection.Selection gesture,
                                               Callback callback) {
        if (!isCurrent(state, frame) || frame.bitmap == null || frame.bitmap.isRecycled()) return;
        Rect bitmapRoi = localBitmapRoi(state.app, frame, gesture);
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        if (bitmapRoi.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreen, new Rect(), new IllegalStateException("empty local OCR ROI"), true));
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(frame.bitmap, bitmapRoi.left, bitmapRoi.top,
                    bitmapRoi.width(), bitmapRoi.height());
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreen, bitmapRoi, t, true));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "start gesture=" + gesture.kind
                + " strategy=frozen_screenshot_direct_mlkit"
                + " roi=" + bitmapRoi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " ppocr=false preindexWait=false");

        CircleStableOcr.recognizeMlKit(state.app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument localBitmap) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument parentBitmap = localBitmap.translated(bitmapRoi.left, bitmapRoi.top,
                            frame.bitmap.getWidth(), frame.bitmap.getHeight());
                    OcrDocument localScreen = frame.transform.documentBitmapToScreen(parentBitmap);
                    OcrDocument initial = selectGesture(state.app, frame, gesture, localScreen);
                    if (!usable(initial)) {
                        initial = gesture.kind == GoogleCircleSelection.Kind.TAP
                                ? nearestGroupDocument(localScreen,
                                frame.bitmapPointToScreen(gesture.focus.x, gesture.focus.y))
                                : localScreen;
                    }

                    if (!usable(localScreen) || !usable(initial)) {
                        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "empty after map gesture="
                                + gesture.kind + " engine=" + localBitmap.engine());
                        callback.onResolved(new Result(Source.NONE, null, null,
                                gestureScreen, bitmapRoi,
                                new IllegalStateException("local screenshot OCR has no selectable text"), true));
                        return;
                    }

                    DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "success gesture="
                            + gesture.kind
                            + " engine=" + localBitmap.engine()
                            + " documentChars=" + localScreen.chars().size()
                            + " selectedChars=" + initial.chars().size()
                            + " text=" + summarize(initial.fullText())
                            + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                            + " coordinateSpace=SCREEN");
                    callback.onResolved(new Result(Source.IMAGE_OCR, localScreen, initial,
                            gestureScreen, bitmapRoi, null, true));
                } catch (Throwable t) {
                    callback.onResolved(new Result(Source.NONE, null, null,
                            gestureScreen, bitmapRoi, t, true));
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "failed gesture="
                        + gesture.kind + " error=" + safe(error)
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreen, bitmapRoi, error, true));
            }
        });
    }

    private static OcrDocument selectGesture(Context app, GoogleCircleCapture.Frame frame,
                                             GoogleCircleSelection.Selection gesture,
                                             OcrDocument document) {
        if (!usable(document)) return null;
        return CircleGestureTextSelector.selectDocument(app, frame, gesture, document,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                "screenshot-gesture-selected");
    }

    private static Rect localBitmapRoi(Context app, GoogleCircleCapture.Frame frame,
                                       GoogleCircleSelection.Selection gesture) {
        int width = frame.bitmap.getWidth();
        int height = frame.bitmap.getHeight();
        if (width <= 0 || height <= 0) return new Rect();

        Rect base = GoogleCircleSelection.exactRectAndClamp(gesture.bounds, width, height);
        if (base.isEmpty()) return new Rect();

        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            int halfW = bitmapPxForDp(app, frame, LOCAL_TAP_HALF_WIDTH_DP);
            int halfH = bitmapPxForDp(app, frame, LOCAL_TAP_HALF_HEIGHT_DP);
            int cx = Math.round(gesture.focus.x);
            int cy = Math.round(gesture.focus.y);
            return clampRect(new Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH),
                    width, height);
        }

        int padX = bitmapPxForDp(app, frame, LOCAL_RANGE_PAD_X_DP);
        int padY = bitmapPxForDp(app, frame, LOCAL_RANGE_PAD_Y_DP);
        Rect expanded = new Rect(base.left - padX, base.top - padY,
                base.right + padX, base.bottom + padY);
        expanded = clampRect(expanded, width, height);
        int minW = bitmapPxForDp(app, frame, LOCAL_MIN_WIDTH_DP);
        int minH = bitmapPxForDp(app, frame, LOCAL_MIN_HEIGHT_DP);
        return ensureMinimumRect(expanded, width, height, minW, minH);
    }

    private static Rect ensureMinimumRect(Rect source, int width, int height, int minW, int minH) {
        if (source == null || source.isEmpty()) return new Rect();
        int targetW = Math.min(width, Math.max(source.width(), Math.max(1, minW)));
        int targetH = Math.min(height, Math.max(source.height(), Math.max(1, minH)));
        int left = source.centerX() - targetW / 2;
        int top = source.centerY() - targetH / 2;
        left = Math.max(0, Math.min(left, width - targetW));
        top = Math.max(0, Math.min(top, height - targetH));
        return new Rect(left, top, left + targetW, top + targetH);
    }

    private static Rect clampRect(Rect source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return new Rect();
        Rect out = new Rect(Math.max(0, source.left), Math.max(0, source.top),
                Math.min(width, source.right), Math.min(height, source.bottom));
        return out.isEmpty() ? new Rect() : out;
    }

    private static int bitmapPxForDp(Context app, GoogleCircleCapture.Frame frame, float dp) {
        float density = app.getResources().getDisplayMetrics().density;
        float screenPx = Math.max(1f, dp * density);
        return Math.max(1, Math.round(frame.transform.screenDistanceToBitmap(screenPx)));
    }

    private static OcrDocument nearestGroupDocument(OcrDocument document, PointF screenPoint) {
        if (!usable(document) || screenPoint == null) return null;
        OcrDocument.CharUnit best = null;
        long bestDistance = Long.MAX_VALUE;
        for (OcrDocument.CharUnit c : document.chars()) {
            Rect r = c.bounds();
            if (r.isEmpty()) continue;
            long dx = screenPoint.x < r.left ? Math.round(r.left - screenPoint.x)
                    : screenPoint.x > r.right ? Math.round(screenPoint.x - r.right) : 0L;
            long dy = screenPoint.y < r.top ? Math.round(r.top - screenPoint.y)
                    : screenPoint.y > r.bottom ? Math.round(screenPoint.y - r.bottom) : 0L;
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = c;
            }
        }
        if (best == null) return null;

        ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
        Rect bounds = null;
        StringBuilder text = new StringBuilder();
        for (OcrDocument.CharUnit c : document.chars()) {
            if (c.line() != best.line() || c.group() != best.group()) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || c.text().isBlank()) continue;
            chars.add(c);
            if (bounds == null) bounds = new Rect(r); else bounds.union(r);
            text.append(c.text());
        }
        String value = text.toString().trim();
        if (chars.isEmpty() || bounds == null || bounds.isEmpty() || value.isEmpty()) return null;
        OcrDocument.Line line = new OcrDocument.Line(value, bounds,
                document.confidence(), chars);
        return OcrDocument.screenSpace(value, List.of(value), List.of(line),
                document.engine() + "+nearest-group", document.confidence(), document.score(),
                document.imageWidth(), document.imageHeight());
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isScreenSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static boolean sameFrame(PreloadState state, GoogleCircleCapture.Frame frame) {
        return state != null && state.frameRef.get() == frame;
    }

    private static boolean isCurrent(PreloadState state, GoogleCircleCapture.Frame frame) {
        synchronized (INDEX_LOCK) {
            return currentIndex == state && sameFrame(state, frame);
        }
    }

    private static Rect gestureScreenBounds(GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        Rect bitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
    }

    private static String summarize(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 96 ? oneLine : oneLine.substring(0, 96) + "…";
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleTextResolver() {}
}
