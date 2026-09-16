package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Frozen-screen text index for the Google-style workspace.
 *
 * The screenshot is recognized once when the workspace opens. View text geometry and OCR character
 * geometry are stored in absolute SCREEN coordinates. Later TAP/HIGHLIGHT/SCRIBBLE gestures only
 * hit-test that frozen index; they never launch another OCR request. This keeps all existing
 * selection/group/handle rendering behavior while making handle expansion independent of the
 * original gesture ROI.
 */
final class GoogleCircleTextResolver {
    enum Source { VIEW, VIEW_OCR, IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        final OcrDocument document;
        final Rect gestureScreenBounds;
        final Rect ocrBitmapRoi;
        final Throwable error;

        Result(Source source, OcrDocument document, Rect gestureScreenBounds,
               Rect ocrBitmapRoi, Throwable error) {
            this.source = source == null ? Source.NONE : source;
            this.document = document;
            this.gestureScreenBounds = gestureScreenBounds == null
                    ? new Rect() : new Rect(gestureScreenBounds);
            this.ocrBitmapRoi = ocrBitmapRoi == null ? new Rect() : new Rect(ocrBitmapRoi);
            this.error = error;
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

        OcrDocument viewDocument;
        OcrDocument exactViewDocument;
        OcrDocument ocrDocument;
        Throwable ocrError;
        boolean viewDone;
        boolean ocrDone;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }

        boolean ready() {
            return viewDone && ocrDone;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService VIEW_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-index-view");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService INDEX_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-index-ocr");
        t.setDaemon(true);
        return t;
    });

    private static final Object INDEX_LOCK = new Object();
    private static final float VIEW_TAP_TOLERANCE_DP = 8f;
    private static long indexGeneration;
    private static PreloadState currentIndex;

    /** Start one frozen-screen index as soon as capture completes. Safe to call more than once. */
    static void preload(Context c, GoogleCircleCapture.Frame frame) {
        if (c == null || frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) return;
        Context app = c.getApplicationContext();
        PreloadState state;
        synchronized (INDEX_LOCK) {
            if (sameFrame(currentIndex, frame)) return;
            state = new PreloadState(++indexGeneration, app, frame);
            currentIndex = state;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "G_CIRCLE_TEXT_INDEX", "start generation=" + state.generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " strategy=frozen_view_plus_full_frame_ocr_once"
                + " coordinateSpace=SCREEN");

        VIEW_IO.execute(() -> prepareViewPart(state, frame, started));
        INDEX_IO.execute(() -> prepareOcrPart(state, frame, started));
    }

    /** Resolve only from the prebuilt index. If it is still building, queue this gesture. */
    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (c == null || frame == null || gesture == null || callback == null) return;
        Context app = c.getApplicationContext();
        preload(app, frame);

        PreloadState state;
        boolean ready;
        OcrDocument earlyExact = null;
        synchronized (INDEX_LOCK) {
            state = sameFrame(currentIndex, frame) ? currentIndex : null;
            if (state == null) {
                callback.onResolved(new Result(Source.NONE, null,
                        gestureScreenBounds(frame, gesture), new Rect(),
                        new IllegalStateException("text index unavailable")));
                return;
            }
            ready = state.ready();
            if (!ready && state.viewDone && state.exactViewDocument != null
                    && documentHitsGesture(app, frame, gesture, state.exactViewDocument,
                    VIEW_TAP_TOLERANCE_DP)) {
                earlyExact = state.exactViewDocument;
            } else if (!ready) {
                state.pending.add(new PendingResolve(gesture, callback));
            }
        }

        if (earlyExact != null) {
            Rect gestureScreen = gestureScreenBounds(frame, gesture);
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "indexed hit source=VIEW"
                    + " chars=" + earlyExact.chars().size()
                    + " indexReady=false fastPath=view_exact");
            callback.onResolved(new Result(Source.VIEW, earlyExact,
                    gestureScreen, new Rect(), null));
            return;
        }
        if (!ready) {
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "wait for preindex gesture="
                    + gesture.kind + " generation=" + state.generation);
            return;
        }
        resolvePrepared(state, frame, gesture, callback);
    }

    private static void prepareViewPart(PreloadState state, GoogleCircleCapture.Frame frame,
                                        long started) {
        OcrDocument view;
        Throwable error = null;
        try {
            view = CircleViewTextSnapshot.capture(state.app).toScreenDocument();
        } catch (Throwable t) {
            error = t;
            view = CircleViewTextSnapshot.empty(frame.screenBounds).toScreenDocument();
        }
        OcrDocument exact = exactViewGeometry(view);
        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return;
            state.viewDocument = view;
            state.exactViewDocument = exact;
            state.viewDone = true;
        }
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "view ready generation="
                + state.generation
                + " rawChars=" + view.chars().size()
                + " exactChars=" + exact.chars().size()
                + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                + (error == null ? "" : " error=" + ScreenCaptureBackend.safeMessage(error)));
        dispatchIfReady(state, frame, started);
    }

    private static void prepareOcrPart(PreloadState state, GoogleCircleCapture.Frame frame,
                                       long started) {
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            finishOcrPart(state, frame, null,
                    new IllegalStateException("frozen screenshot unavailable"), started);
            return;
        }

        final Bitmap copy;
        try {
            Bitmap made = source.copy(Bitmap.Config.ARGB_8888, false);
            if (made == null) throw new IllegalStateException("OCR frame copy failed");
            copy = made;
        } catch (Throwable t) {
            finishOcrPart(state, frame, null, t, started);
            return;
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "ocr begin generation="
                + state.generation + " bitmap=" + copy.getWidth() + "x" + copy.getHeight()
                + " roi=full_frozen_frame requestsPerWorkspace=1");

        OcrEngine.recognizeDocument(state.app, copy, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                OcrDocument screen = translateToScreen(frame,
                        new Rect(0, 0, copy.getWidth(), copy.getHeight()), document);
                recycle(copy);
                finishOcrPart(state, frame, screen, null, started);
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
            state.ocrDocument = document;
            state.ocrError = error;
            state.ocrDone = true;
        }
        int chars = document == null ? 0 : document.chars().size();
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "ocr ready generation="
                + state.generation
                + " usable=" + (document != null && document.isScreenSpace() && chars > 0)
                + " chars=" + chars
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
                + " viewExactChars=" + (state.exactViewDocument == null
                ? 0 : state.exactViewDocument.chars().size())
                + " ocrChars=" + (state.ocrDocument == null ? 0 : state.ocrDocument.chars().size())
                + " pending=" + pending.size()
                + " totalMs=" + (android.os.SystemClock.uptimeMillis() - started));

        if (pending.isEmpty()) return;
        MAIN.post(() -> {
            GoogleCircleCapture.Frame liveFrame = state.frameRef.get();
            if (liveFrame == null || liveFrame != frame) return;
            for (PendingResolve request : pending) {
                resolvePrepared(state, frame, request.gesture, request.callback);
            }
        });
    }

    private static void resolvePrepared(PreloadState state, GoogleCircleCapture.Frame frame,
                                        GoogleCircleSelection.Selection gesture, Callback callback) {
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        OcrDocument exact = state.exactViewDocument;
        if (exact != null && documentHitsGesture(state.app, frame, gesture, exact,
                VIEW_TAP_TOLERANCE_DP)) {
            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "indexed hit gesture="
                    + gesture.kind + " source=VIEW chars=" + exact.chars().size()
                    + " preIndex=true coordinateSpace=SCREEN");
            callback.onResolved(new Result(Source.VIEW, exact, gestureScreen, new Rect(), null));
            return;
        }

        OcrDocument ocr = state.ocrDocument;
        if (ocr != null && ocr.isScreenSpace() && !ocr.chars().isEmpty()) {
            boolean coarseViewHit = state.viewDocument != null
                    && documentHitsGesture(state.app, frame, gesture, state.viewDocument,
                    VIEW_TAP_TOLERANCE_DP);
            Source source = coarseViewHit ? Source.VIEW_OCR : Source.IMAGE_OCR;
            Rect fullRoi = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "indexed hit gesture="
                    + gesture.kind + " source=" + source
                    + " chars=" + ocr.chars().size()
                    + " preIndex=true coordinateSpace=SCREEN");
            callback.onResolved(new Result(source, ocr, gestureScreen, fullRoi, null));
            return;
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "indexed miss gesture="
                + gesture.kind + " preIndex=true ocrAvailable=false");
        callback.onResolved(new Result(Source.NONE, null, gestureScreen, new Rect(), state.ocrError));
    }

    private static boolean sameFrame(PreloadState state, GoogleCircleCapture.Frame frame) {
        return state != null && state.frameRef.get() == frame;
    }

    /**
     * CircleViewTextSnapshot marks true Accessibility character boxes with confidence 1.0 and its
     * synthetic fallback with 0.92. Only true boxes may be rendered directly.
     */
    private static OcrDocument exactViewGeometry(OcrDocument source) {
        if (source == null || !source.isScreenSpace() || source.lines().isEmpty()) {
            return emptyScreenDocument(source, "view-exact-empty");
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int order = 0;
        for (OcrDocument.Line line : source.lines()) {
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect lineBounds = null;
            StringBuilder text = new StringBuilder();
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null || c.text().isBlank() || c.bounds().isEmpty()
                        || c.confidence() < 0.999f) continue;
                Rect bounds = c.bounds();
                if (lineBounds == null) lineBounds = new Rect(bounds); else lineBounds.union(bounds);
                chars.add(new OcrDocument.CharUnit(c.text(), bounds, c.confidence(),
                        lineId, c.group(), order++));
                text.append(c.text());
            }
            if (chars.isEmpty() || lineBounds == null || lineBounds.isEmpty()) continue;
            String row = text.toString();
            lines.add(new OcrDocument.Line(row, lineBounds, 1f, chars));
            blocks.add(row);
            if (full.length() > 0) full.append('\n');
            full.append(row);
            lineId++;
        }

        return OcrDocument.screenSpace(full.toString(), blocks, lines,
                "view-exact", 1f, source.score(),
                source.imageWidth(), source.imageHeight());
    }

    private static OcrDocument emptyScreenDocument(OcrDocument source, String engine) {
        int width = source == null ? 1 : source.imageWidth();
        int height = source == null ? 1 : source.imageHeight();
        return OcrDocument.screenSpace("", List.of(), List.of(), engine, 1f, 0d,
                Math.max(1, width), Math.max(1, height));
    }

    private static boolean documentHitsGesture(Context app, GoogleCircleCapture.Frame frame,
                                               GoogleCircleSelection.Selection gesture,
                                               OcrDocument document, float tapToleranceDp) {
        if (document == null || !document.isScreenSpace() || document.chars().isEmpty()) return false;
        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            PointF screenPoint = bitmapPointToScreen(frame, gesture.focus);
            float tolerance = dp(app, tapToleranceDp);
            float best = Float.MAX_VALUE;
            for (OcrDocument.CharUnit c : document.chars()) {
                Rect r = c.bounds();
                if (r.isEmpty()) continue;
                if (r.contains(Math.round(screenPoint.x), Math.round(screenPoint.y))) return true;
                float dx = screenPoint.x < r.left ? r.left - screenPoint.x
                        : screenPoint.x > r.right ? screenPoint.x - r.right : 0f;
                float dy = screenPoint.y < r.top ? r.top - screenPoint.y
                        : screenPoint.y > r.bottom ? screenPoint.y - r.bottom : 0f;
                best = Math.min(best, (float) Math.hypot(dx, dy));
            }
            return best <= tolerance;
        }

        Rect exact = gestureScreenBounds(frame, gesture);
        if (exact.isEmpty()) return false;
        int tolerance = Math.max(1, Math.round(dp(app, 5f)));
        Rect tolerant = new Rect(exact);
        tolerant.inset(-tolerance, -tolerance);
        tolerant.intersect(frame.screenBounds);
        for (OcrDocument.CharUnit c : document.chars()) {
            Rect r = c.bounds();
            if (!r.isEmpty() && Rect.intersects(tolerant, r)) return true;
        }
        return false;
    }

    private static OcrDocument translateToScreen(GoogleCircleCapture.Frame frame, Rect roi,
                                                 OcrDocument local) {
        if (local == null || local.lines().isEmpty()) {
            return OcrDocument.screenSpace(local == null ? "" : local.fullText(),
                    local == null ? List.of() : local.blocks(), List.of(),
                    local == null ? "preindex-ocr" : "preindex-" + local.engine(),
                    local == null ? 0f : local.confidence(),
                    local == null ? 0d : local.score(),
                    Math.max(1, frame.screenBounds.width()),
                    Math.max(1, frame.screenBounds.height()));
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int order = 0;
        for (OcrDocument.Line line : local.lines()) {
            Rect lineBitmap = offsetAndClamp(line.bounds(), roi,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
            if (lineBitmap.isEmpty()) continue;
            Rect lineScreen = frame.bitmapRectToScreen(lineBitmap);
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : line.chars()) {
                Rect charBitmap = offsetAndClamp(c.bounds(), roi,
                        frame.bitmap.getWidth(), frame.bitmap.getHeight());
                if (charBitmap.isEmpty()) continue;
                Rect charScreen = frame.bitmapRectToScreen(charBitmap);
                if (charScreen.isEmpty()) continue;
                chars.add(new OcrDocument.CharUnit(c.text(), charScreen, c.confidence(),
                        lineId, c.group(), order++));
            }
            if (!chars.isEmpty()) {
                lines.add(new OcrDocument.Line(line.text(), lineScreen,
                        line.confidence(), chars));
                lineId++;
            }
        }

        return OcrDocument.screenSpace(local.fullText(), local.blocks(), lines,
                "preindex-" + local.engine(), local.confidence(), local.score(),
                Math.max(1, frame.screenBounds.width()),
                Math.max(1, frame.screenBounds.height()));
    }

    private static Rect offsetAndClamp(Rect local, Rect roi, int width, int height) {
        if (local == null || local.isEmpty()) return new Rect();
        Rect out = new Rect(local);
        out.offset(roi.left, roi.top);
        if (!out.intersect(0, 0, width, height)) return new Rect();
        return out;
    }

    private static Rect gestureScreenBounds(GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        Rect bitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
    }

    private static PointF bitmapPointToScreen(GoogleCircleCapture.Frame frame, PointF bitmapPoint) {
        float sx = frame.screenBounds.width() / (float) Math.max(1, frame.bitmap.getWidth());
        float sy = frame.screenBounds.height() / (float) Math.max(1, frame.bitmap.getHeight());
        return new PointF(frame.screenBounds.left + bitmapPoint.x * sx,
                frame.screenBounds.top + bitmapPoint.y * sy);
    }

    private static float dp(Context app, float value) {
        return value * app.getResources().getDisplayMetrics().density;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleTextResolver() {}
}
