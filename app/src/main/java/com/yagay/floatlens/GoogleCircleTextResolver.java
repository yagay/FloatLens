package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OCR-only resolver backed by one stable screen document.
 *
 * <p>The frozen screenshot is preprocessed once by {@link CircleTextOcrPipeline}: PP-OCR locates
 * text regions when a model is installed, then ML Kit performs one complete-document recognition.
 * There are no tiles, no AKS/global pass merge, no character fusion, and no gesture-time OCR.</p>
 */
final class GoogleCircleTextResolver {
    enum Source { VIEW, VIEW_OCR, IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        /** Complete single-source SCREEN-space OCR document. */
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

    private static final class PendingResolve {
        final GoogleCircleSelection.Selection gesture;
        final Callback callback;

        PendingResolve(GoogleCircleSelection.Selection gesture, Callback callback) {
            this.gesture = gesture;
            this.callback = callback;
        }
    }

    private static final class PreloadState {
        final long generation;
        final Context app;
        final WeakReference<GoogleCircleCapture.Frame> frameRef;
        final ArrayList<PendingResolve> pending = new ArrayList<>();

        OcrDocument screenDocument;
        Throwable ocrError;
        boolean ocrDone;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }

        boolean ready() { return ocrDone; }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService INDEX_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-single-ocr");
        t.setDaemon(true);
        return t;
    });

    private static final Object INDEX_LOCK = new Object();
    private static final float CACHED_OCR_TAP_TOLERANCE_DP = 0f;
    private static final float RANGE_CORRIDOR_DP = 6f;

    private static long indexGeneration;
    private static PreloadState currentIndex;

    static void preload(Context c, GoogleCircleCapture.Frame frame) {
        if (c == null || frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) return;
        Context app = c.getApplicationContext();
        PreloadState state;
        PreloadState replaced = null;
        synchronized (INDEX_LOCK) {
            if (sameFrame(currentIndex, frame)) return;
            if (currentIndex != null) {
                replaced = currentIndex;
                clearStateLocked(replaced);
            }
            state = new PreloadState(++indexGeneration, app, frame);
            currentIndex = state;
        }
        if (replaced != null) {
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_INDEX", "replace stale generation="
                    + replaced.generation + " with=" + state.generation);
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "G_CIRCLE_TEXT_INDEX", "start generation=" + state.generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " strategy=pp_detect_then_single_mlkit"
                + " textOwner=OCR_ONLY"
                + " preindex=single_document"
                + " tiles=false aksMerge=false characterFusion=false localOcr=false"
                + " selectionModel=global_single_document_plus_range"
                + " viewText=false semanticLabels=false"
                + " geometry=shared_matrix_transform coordinateSpace=SCREEN");

        INDEX_IO.execute(() -> prepareOcrPart(state, frame, started));
    }

    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (c == null || frame == null || gesture == null || callback == null) return;
        Context app = c.getApplicationContext();
        preload(app, frame);

        PreloadState state;
        boolean ready;
        synchronized (INDEX_LOCK) {
            state = sameFrame(currentIndex, frame) ? currentIndex : null;
            if (state == null) {
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreenBounds(frame, gesture), new Rect(),
                        new IllegalStateException("OCR document unavailable"), false));
                return;
            }
            ready = state.ready();
            if (!ready) state.pending.add(new PendingResolve(gesture, callback));
        }

        if (!ready) {
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "wait for single OCR document gesture="
                    + gesture.kind + " generation=" + state.generation);
            return;
        }
        resolvePrepared(state, frame, gesture, callback);
    }

    static void release(Context c, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = c == null ? null : c.getApplicationContext();
        PreloadState released;
        int chars;
        int lines;
        int pending;
        synchronized (INDEX_LOCK) {
            if (!sameFrame(currentIndex, frame)) return;
            released = currentIndex;
            chars = released.screenDocument == null ? 0 : released.screenDocument.chars().size();
            lines = released.screenDocument == null ? 0 : released.screenDocument.lines().size();
            pending = released.pending.size();
            currentIndex = null;
            indexGeneration++;
            clearStateLocked(released);
        }
        Context logContext = app == null ? released.app : app;
        DiagnosticLog.i(logContext, "G_CIRCLE_TEXT_INDEX", "release generation="
                + released.generation
                + " reason=" + (reason == null ? "unknown" : reason)
                + " chars=" + chars
                + " lines=" + lines
                + " pendingCleared=" + pending);
    }

    private static void clearStateLocked(PreloadState state) {
        if (state == null) return;
        state.pending.clear();
        state.screenDocument = null;
        state.ocrError = null;
        state.ocrDone = false;
    }

    private static void prepareOcrPart(PreloadState state, GoogleCircleCapture.Frame frame,
                                       long started) {
        if (!isCurrent(state, frame)) return;
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            finishOcrPart(state, frame, null,
                    new IllegalStateException("frozen screenshot unavailable"), started);
            return;
        }

        final Bitmap copy;
        try {
            copy = source.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("OCR frame copy failed");
        } catch (Throwable t) {
            finishOcrPart(state, frame, null, t, started);
            return;
        }

        if (!isCurrent(state, frame)) {
            recycle(copy);
            return;
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "ocr begin generation="
                + state.generation + " bitmap=" + copy.getWidth() + "x" + copy.getHeight()
                + " pipeline=pp_detect_then_single_mlkit"
                + " tiles=false aksMerge=false characterFusion=false"
                + " viewMask=false semanticLabels=false");

        CircleTextOcrPipeline.recognize(state.app, copy,
                () -> !isCurrent(state, frame), new CircleTextOcrPipeline.Callback() {
            @Override public void onSuccess(OcrDocument bitmapDocument) {
                OcrDocument screenDocument = bitmapDocument == null
                        ? null : frame.transform.documentBitmapToScreen(bitmapDocument);
                recycle(copy);
                finishOcrPart(state, frame, screenDocument, null, started);
            }

            @Override public void onFailure(Throwable error) {
                recycle(copy);
                finishOcrPart(state, frame, null, error, started);
            }
        });
    }

    private static void finishOcrPart(PreloadState state, GoogleCircleCapture.Frame frame,
                                      OcrDocument document, Throwable error, long started) {
        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return;
            state.screenDocument = document;
            state.ocrError = error;
            state.ocrDone = true;
        }
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "ocr ready generation="
                + state.generation
                + " usable=" + usable(document)
                + " chars=" + (document == null ? 0 : document.chars().size())
                + " lines=" + (document == null ? 0 : document.lines().size())
                + " engine=" + (document == null ? "none" : document.engine())
                + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                + (error == null ? "" : " error=" + ScreenCaptureBackend.safeMessage(error)));
        dispatchIfReady(state, frame, started);
    }

    private static void dispatchIfReady(PreloadState state, GoogleCircleCapture.Frame frame,
                                        long started) {
        ArrayList<PendingResolve> pending;
        synchronized (INDEX_LOCK) {
            if (currentIndex != state || !state.ready()) return;
            pending = new ArrayList<>(state.pending);
            state.pending.clear();
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "ready generation=" + state.generation
                + " textOwner=OCR_ONLY"
                + " engine=" + (state.screenDocument == null ? "none" : state.screenDocument.engine())
                + " chars=" + (state.screenDocument == null ? 0 : state.screenDocument.chars().size())
                + " lines=" + (state.screenDocument == null ? 0 : state.screenDocument.lines().size())
                + " pending=" + pending.size()
                + " totalMs=" + (android.os.SystemClock.uptimeMillis() - started));

        if (pending.isEmpty()) return;
        MAIN.post(() -> {
            if (!isCurrent(state, frame)) return;
            GoogleCircleCapture.Frame liveFrame = state.frameRef.get();
            if (liveFrame == null || liveFrame != frame) return;
            for (PendingResolve request : pending) {
                if (!isCurrent(state, frame)) return;
                resolvePrepared(state, frame, request.gesture, request.callback);
            }
        });
    }

    private static void resolvePrepared(PreloadState state, GoogleCircleCapture.Frame frame,
                                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (!isCurrent(state, frame)) return;
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        OcrDocument document = state.screenDocument;
        if (!usable(document) || gestureScreen.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreen, new Rect(), state.ocrError, false));
            return;
        }

        OcrDocument initial = CircleGestureTextSelector.selectDocument(
                state.app, frame, gesture, document,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                "single-ocr-gesture-selected");
        if (!usable(initial)) {
            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "single document miss gesture="
                    + gesture.kind
                    + " documentChars=" + document.chars().size()
                    + " engine=" + document.engine()
                    + " localOcr=false");
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreen, new Rect(), state.ocrError, false));
            return;
        }

        Rect fullBitmap = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "single document hit gesture="
                + gesture.kind
                + " documentChars=" + document.chars().size()
                + " initialChars=" + initial.chars().size()
                + " initialText=" + summarize(initial.fullText())
                + " engine=" + document.engine()
                + " selectionModel=global_single_document_plus_initial_range"
                + " tiles=false aksMerge=false characterFusion=false localOcr=false"
                + " coordinateSpace=SCREEN");
        callback.onResolved(new Result(Source.IMAGE_OCR, document,
                initial, gestureScreen, fullBitmap, null, false));
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

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleTextResolver() {}
}
