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
 * OCR-only text resolver for the Google-style Circle workspace.
 *
 * <p>Circle pre-indexes the frozen screenshot with the AKS spatial strategy: one full-frame OCR
 * pass plus four overlapping 60% quadrant passes. The five recognizer documents remain completely
 * independent. At gesture time we query only passes covering the target and choose one complete
 * recognizer result; documents, lines and characters are never merged across passes.</p>
 */
final class GoogleCircleTextResolver {
    // VIEW/VIEW_OCR are retained only for binary/source compatibility with older callers. Google
    // Circle emits IMAGE_OCR or NONE only.
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
        final boolean localFallback;

        Result(Source source, OcrDocument document, Rect gestureScreenBounds,
               Rect ocrBitmapRoi, Throwable error, boolean localFallback) {
            this.source = source == null ? Source.NONE : source;
            this.document = document;
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

        CircleOcrIndex ocrIndex;
        Throwable ocrError;
        boolean ocrDone;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }

        boolean ready() { return ocrDone; }
    }

    private static final class IndexedHit {
        final CircleOcrIndex.Entry entry;
        final OcrDocument document;
        final float spatialScore;

        IndexedHit(CircleOcrIndex.Entry entry, OcrDocument document, float spatialScore) {
            this.entry = entry;
            this.document = document;
            this.spatialScore = spatialScore;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService INDEX_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-index-ocr");
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
                + " strategy=aks_full_plus_4_overlap_tiles"
                + " textOwner=OCR_ONLY"
                + " preindex=full+tl+tr+bl+br"
                + " tileFraction=0.60"
                + " merge=false characterFusion=false localOcr=false"
                + " viewText=false semanticLabels=false"
                + " geometry=shared_matrix_transform"
                + " coordinateSpace=SCREEN");

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
                callback.onResolved(new Result(Source.NONE, null,
                        gestureScreenBounds(frame, gesture), new Rect(),
                        new IllegalStateException("OCR index unavailable"), false));
                return;
            }
            ready = state.ready();
            if (!ready) state.pending.add(new PendingResolve(gesture, callback));
        }

        if (!ready) {
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "wait for AKS preindex gesture="
                    + gesture.kind + " generation=" + state.generation);
            return;
        }
        resolvePrepared(state, frame, gesture, callback);
    }

    /** Release all workspace-scoped OCR documents and pending callbacks when the overlay closes. */
    static void release(Context c, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = c == null ? null : c.getApplicationContext();
        PreloadState released;
        int ocrPasses;
        int ocrChars;
        int pending;
        synchronized (INDEX_LOCK) {
            if (!sameFrame(currentIndex, frame)) return;
            released = currentIndex;
            ocrPasses = released.ocrIndex == null ? 0 : released.ocrIndex.passCount();
            ocrChars = released.ocrIndex == null ? 0 : released.ocrIndex.totalChars();
            pending = released.pending.size();
            currentIndex = null;
            indexGeneration++;
            clearStateLocked(released);
        }
        Context logContext = app == null ? released.app : app;
        DiagnosticLog.i(logContext, "G_CIRCLE_TEXT_INDEX", "release generation="
                + released.generation
                + " reason=" + (reason == null ? "unknown" : reason)
                + " ocrPasses=" + ocrPasses
                + " ocrChars=" + ocrChars
                + " pendingCleared=" + pending);
    }

    private static void clearStateLocked(PreloadState state) {
        if (state == null) return;
        state.pending.clear();
        state.ocrIndex = null;
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
                + " passes=5 tileFraction=0.60"
                + " merge=false characterFusion=false"
                + " viewMask=false semanticLabels=false"
                + " enginePolicy=follow_main_setting");

        CirclePreindexTiledOcr.recognize(state.app, copy,
                () -> !isCurrent(state, frame), new CirclePreindexTiledOcr.Callback() {
            @Override public void onSuccess(CircleOcrIndex bitmapIndex) {
                CircleOcrIndex screenIndex = bitmapIndex == null
                        ? null : bitmapIndex.toScreen(frame.transform);
                recycle(copy);
                finishOcrPart(state, frame, screenIndex, null, started);
            }

            @Override public void onFailure(Throwable error) {
                recycle(copy);
                finishOcrPart(state, frame, null, error, started);
            }
        });
    }

    private static void finishOcrPart(PreloadState state, GoogleCircleCapture.Frame frame,
                                      CircleOcrIndex index, Throwable error, long started) {
        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return;
            state.ocrIndex = index;
            state.ocrError = error;
            state.ocrDone = true;
        }
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "ocr ready generation="
                + state.generation
                + " usable=" + (index != null && !index.isEmpty())
                + " passes=" + (index == null ? 0 : index.passCount())
                + " totalChars=" + (index == null ? 0 : index.totalChars())
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
                + " ocrPasses=" + (state.ocrIndex == null ? 0 : state.ocrIndex.passCount())
                + " ocrCharsTotal=" + (state.ocrIndex == null ? 0 : state.ocrIndex.totalChars())
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
        CircleOcrIndex index = state.ocrIndex;
        if (index == null || index.isEmpty() || gestureScreen.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, new Rect(),
                    state.ocrError, false));
            return;
        }

        ArrayList<IndexedHit> hits = new ArrayList<>();
        for (CircleOcrIndex.Entry entry : index.entries()) {
            if (entry == null || !usable(entry.document)
                    || !coverageTouchesGesture(entry.coverage, gestureScreen)) continue;
            OcrDocument scoped = CircleGestureTextSelector.selectDocument(
                    state.app, frame, gesture, entry.document,
                    CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                    entry.source + "-gesture-selected");
            if (!usable(scoped)) continue;
            hits.add(new IndexedHit(entry, scoped,
                    spatialScore(entry.coverage, gestureScreen, entry.fullFrame)));
        }

        IndexedHit best = chooseBest(hits);
        if (best == null) {
            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "index miss gesture="
                    + gesture.kind
                    + " passesAvailable=" + index.passCount()
                    + " localOcr=false");
            callback.onResolved(new Result(Source.NONE, null,
                    gestureScreen, new Rect(), state.ocrError, false));
            return;
        }

        Rect bitmapRoi = frame.screenRectToBitmap(best.entry.coverage);
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "index hit gesture="
                + gesture.kind
                + " passHits=" + hits.size()
                + " selectedSource=" + best.entry.source
                + " fullFrame=" + best.entry.fullFrame
                + " spatialScore=" + String.format(java.util.Locale.ROOT, "%.3f", best.spatialScore)
                + " chars=" + best.document.chars().size()
                + " text=" + summarize(best.document.fullText())
                + " merge=false characterFusion=false localOcr=false"
                + " coordinateSpace=SCREEN");
        callback.onResolved(new Result(Source.IMAGE_OCR, best.document,
                gestureScreen, bitmapRoi, null, false));
    }

    /**
     * Prefer a regional pass when it has a safe view of the target, as in AKS, but do not use the
     * recognized text itself as a voting signal. This keeps source choice deterministic even when
     * recognizers disagree about the characters.
     */
    private static IndexedHit chooseBest(ArrayList<IndexedHit> hits) {
        IndexedHit best = null;
        for (IndexedHit hit : hits) {
            if (hit == null) continue;
            if (best == null || hit.spatialScore > best.spatialScore) {
                best = hit;
                continue;
            }
            if (Math.abs(hit.spatialScore - best.spatialScore) < 0.0001f) {
                float hc = reportedConfidence(hit.document);
                float bc = reportedConfidence(best.document);
                if (hc > bc) {
                    best = hit;
                    continue;
                }
                if (hc == bc && hit.document.score() > best.document.score()) best = hit;
            }
        }
        return best;
    }

    /**
     * Score only geometry/source context, never recognized content. A tile receives an AKS-style
     * preference when the target lies comfortably inside it; targets near a tile crop boundary are
     * penalized so an unclipped full-frame result can win instead.
     */
    private static float spatialScore(Rect coverage, Rect target, boolean fullFrame) {
        if (coverage == null || coverage.isEmpty() || target == null || target.isEmpty()) return -1f;
        float cx = target.exactCenterX();
        float cy = target.exactCenterY();
        float left = cx - coverage.left;
        float right = coverage.right - cx;
        float top = cy - coverage.top;
        float bottom = coverage.bottom - cy;
        float xMargin = Math.max(0f, Math.min(left, right)) / Math.max(1f, coverage.width());
        float yMargin = Math.max(0f, Math.min(top, bottom)) / Math.max(1f, coverage.height());
        float interior = Math.min(xMargin, yMargin); // 0 at crop edge, ~0.5 at center.

        if (fullFrame) return 1.0f + interior;
        // A regional OCR pass is preferred only when the target is not at its crop edge.
        float tileBonus = interior >= 0.04f ? 1.0f : -0.20f;
        return 1.0f + tileBonus + interior * 2.0f;
    }

    private static boolean coverageTouchesGesture(Rect coverage, Rect gestureScreen) {
        if (coverage == null || coverage.isEmpty() || gestureScreen == null || gestureScreen.isEmpty()) {
            return false;
        }
        if (coverage.contains(Math.round(gestureScreen.exactCenterX()),
                Math.round(gestureScreen.exactCenterY()))) return true;
        return Rect.intersects(coverage, gestureScreen);
    }

    private static float reportedConfidence(OcrDocument document) {
        if (document == null) return 0f;
        float value = document.confidence();
        return value > 0f ? value : 0f;
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
