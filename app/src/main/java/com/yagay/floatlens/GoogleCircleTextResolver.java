package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
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
 * <p>There is one text owner: OCR. Circle performs one frozen full-frame OCR pass for ordinary
 * screen text. Gesture resolution reads only that OCR geometry. A cache miss, or a compact CJK-like
 * image-text hit, is verified with the tight gesture-local three-variant OCR fallback. Accessibility
 * View text, semantic labels and View/OCR geometry refinement are deliberately excluded from this
 * pipeline so two text sources can never compete for the same screen location.</p>
 */
final class GoogleCircleTextResolver {
    // VIEW/VIEW_OCR are retained only for binary/source compatibility with callers compiled against
    // older revisions. The Google Circle pipeline now emits IMAGE_OCR or NONE only.
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

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService INDEX_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-index-ocr");
        t.setDaemon(true);
        return t;
    });

    private static final Object INDEX_LOCK = new Object();
    private static final float CACHED_OCR_TAP_TOLERANCE_DP = 0f;
    private static final float LOCAL_OCR_TAP_TOLERANCE_DP = 28f;
    private static final float RANGE_CORRIDOR_DP = 6f;

    private static final float LOCAL_TAP_HALF_SIZE_DP = 38f;
    private static final float LOCAL_VERIFY_PAD_DP = 18f;
    // The crop must contain the full OCR stroke corridor. Pixels outside that corridor are
    // neutralized before recognition for range gestures, so this padding does not reintroduce the
    // old rectangular-interference problem.
    private static final float LOCAL_RANGE_PAD_DP = 20f;

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
                + " strategy=ocr_only_full_then_local3"
                + " textOwner=OCR_ONLY"
                + " preindex=full_once"
                + " fallback=gesture_local_three_variant"
                + " viewText=false viewMask=false semanticLabels=false"
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
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "wait for full OCR gesture="
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

        // Keep OCR input independent from the workspace bitmap lifetime. The pixels are unmodified:
        // no View mask, semantic mask or accessibility-derived preprocessing is applied.
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
                + " roi=full"
                + " requestsPerWorkspace=1"
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
        OcrDocument full = state.ocrIndex == null ? null : state.ocrIndex.fullFrameDocument();
        OcrDocument scoped = usable(full)
                ? CircleGestureTextSelector.selectDocument(state.app, frame, gesture, full,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP, "full-gesture-selected")
                : null;

        if (usable(scoped)) {
            boolean verifyCompact = shouldVerifyCompactImageCandidate(state.app, scoped);
            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "full hit gesture="
                    + gesture.kind
                    + " chars=" + scoped.chars().size()
                    + " text=" + summarize(scoped.fullText())
                    + " compactVerify=" + verifyCompact
                    + " textOwner=OCR_ONLY"
                    + " preIndex=true coordinateSpace=SCREEN");
            if (verifyCompact) {
                resolveLocalEnhancedOcr(state, frame, gesture, gestureScreen, callback,
                        scoped, true);
                return;
            }

            Rect fullRoi = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
            callback.onResolved(new Result(Source.IMAGE_OCR, scoped,
                    gestureScreen, fullRoi, null, false));
            return;
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "full miss gesture="
                + gesture.kind
                + " fullOcrAvailable=" + usable(full)
                + " -> local three-variant OCR");
        resolveLocalEnhancedOcr(state, frame, gesture, gestureScreen, callback, null, false);
    }

    private static void resolveLocalEnhancedOcr(PreloadState state,
                                                GoogleCircleCapture.Frame frame,
                                                GoogleCircleSelection.Selection gesture,
                                                Rect gestureScreen,
                                                Callback callback,
                                                OcrDocument cachedFallback,
                                                boolean compactVerification) {
        if (!isCurrent(state, frame)) return;
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            returnCachedOrError(callback, cachedFallback, gestureScreen, new Rect(),
                    new IllegalStateException("frozen screenshot unavailable"));
            return;
        }

        Rect roi = localRecognitionRoi(state.app, frame, gesture,
                compactVerification ? cachedFallback : null);
        if (roi.isEmpty()) {
            returnCachedOrError(callback, cachedFallback, gestureScreen, roi, state.ocrError);
            return;
        }

        final Bitmap crop;
        final GestureOcrCorridorMask.Result corridor;
        try {
            Bitmap made = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
            if (made == source || !made.isMutable()) {
                Bitmap mutable = made.copy(Bitmap.Config.ARGB_8888, true);
                if (mutable == null) throw new IllegalStateException("local OCR crop copy failed");
                if (made != source) recycle(made);
                made = mutable;
            }
            crop = made;
            corridor = compactVerification
                    ? new GestureOcrCorridorMask.Result(false, gesture.points.size(), 0f)
                    : GestureOcrCorridorMask.apply(state.app, crop, roi, gesture, frame.transform);
        } catch (Throwable t) {
            returnCachedOrError(callback, cachedFallback, gestureScreen, roi, t);
            return;
        }

        if (!isCurrent(state, frame)) {
            recycle(crop);
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "start gesture=" + gesture.kind
                + " roi=" + roi.toShortString()
                + " input=" + crop.getWidth() + "x" + crop.getHeight()
                + " reason=" + (compactVerification ? "compact_full_verify" : "full_miss")
                + " enhancement=three_variant"
                + " variants=scaled,contrast,light_binary"
                + " viewMask=false semanticLabels=false"
                + " corridorApplied=" + corridor.applied
                + " corridorPoints=" + corridor.pointCount
                + " corridorHalfWidthBitmapPx=" + Math.round(corridor.halfWidthBitmapPx)
                + " cachedFallback=" + (cachedFallback != null)
                + " geometry=roi_to_screen_matrix"
                + " enginePolicy=follow_main_setting"
                + " pixelOnly=true nearbyCaptionSearch=false");

        CircleLocalOcrFallback.recognize(state.app, crop,
                () -> !isCurrent(state, frame), new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (!isCurrent(state, frame)) {
                    recycle(crop);
                    return;
                }
                OcrDocument screen = frame.transform.documentLocalToScreen(document, roi,
                        crop.getWidth(), crop.getHeight(), "local-enhanced-");
                recycle(crop);
                OcrDocument localScoped = CircleGestureTextSelector.selectDocument(
                        state.app, frame, gesture, screen,
                        LOCAL_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                        "local-gesture-selected");
                boolean hit = usable(localScoped);
                boolean compatible = !compactVerification
                        || verificationLengthCompatible(cachedFallback, localScoped);
                OcrDocument selected = hit && compatible ? localScoped : cachedFallback;
                boolean usedLocal = selected == localScoped && usable(localScoped);
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "done gesture=" + gesture.kind
                        + " hit=" + hit
                        + " compatible=" + compatible
                        + " usedLocal=" + usedLocal
                        + " chars=" + (localScoped == null ? 0 : localScoped.chars().size())
                        + " text=" + summarize(localScoped == null ? "" : localScoped.fullText())
                        + " cachedText=" + summarize(cachedFallback == null ? "" : cachedFallback.fullText())
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                        + " coordinateSpace=SCREEN pixelOnly=true");
                if (usable(selected)) {
                    callback.onResolved(new Result(Source.IMAGE_OCR, selected,
                            gestureScreen, roi, null, usedLocal));
                } else {
                    callback.onResolved(new Result(Source.NONE, null,
                            gestureScreen, roi, null, true));
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "failed gesture="
                        + gesture.kind + " error=" + ScreenCaptureBackend.safeMessage(error)
                        + " cachedFallback=" + (cachedFallback != null));
                returnCachedOrError(callback, cachedFallback, gestureScreen, roi, error);
            }
        });
    }

    private static void returnCachedOrError(Callback callback, OcrDocument cachedFallback,
                                            Rect gestureScreen, Rect roi, Throwable error) {
        if (callback == null) return;
        if (usable(cachedFallback)) {
            callback.onResolved(new Result(Source.IMAGE_OCR, cachedFallback,
                    gestureScreen, roi, error, false));
        } else {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, error, true));
        }
    }

    private static Rect localRecognitionRoi(Context app, GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture,
                                            OcrDocument preferredTarget) {
        if (frame == null || gesture == null || frame.bitmap == null) return new Rect();

        if (usable(preferredTarget)) {
            Rect target = documentBounds(preferredTarget);
            if (!target.isEmpty()) {
                int pad = Math.max(1, Math.round(dp(app, LOCAL_VERIFY_PAD_DP)));
                target.inset(-pad, -pad);
                if (target.intersect(frame.screenBounds)) {
                    return frame.screenRectToBitmap(target);
                }
            }
        }

        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            PointF focus = frame.bitmapPointToScreen(gesture.focus.x, gesture.focus.y);
            float half = dp(app, LOCAL_TAP_HALF_SIZE_DP);
            Rect screenRoi = new Rect(
                    (int) Math.floor(focus.x - half),
                    (int) Math.floor(focus.y - half),
                    (int) Math.ceil(focus.x + half),
                    (int) Math.ceil(focus.y + half));
            shiftIntoBounds(screenRoi, frame.screenBounds);
            if (!screenRoi.intersect(frame.screenBounds)) return new Rect();
            return frame.screenRectToBitmap(screenRoi);
        }

        Rect exactBitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        if (exactBitmap.isEmpty()) return new Rect();
        Rect screenRoi = frame.bitmapRectToScreen(exactBitmap);
        if (screenRoi.isEmpty()) return new Rect();
        int pad = Math.max(1, Math.round(dp(app, LOCAL_RANGE_PAD_DP)));
        screenRoi.inset(-pad, -pad);
        if (!screenRoi.intersect(frame.screenBounds)) return new Rect();
        return frame.screenRectToBitmap(screenRoi);
    }

    private static boolean shouldVerifyCompactImageCandidate(Context app, OcrDocument document) {
        if (app == null || !usable(document)) return false;
        String text = document.fullText();
        if (text == null || text.isBlank()) return false;
        int total = 0;
        int cjk = 0;
        for (int cp : text.codePoints().toArray()) {
            if (Character.isWhitespace(cp) || (!Character.isLetterOrDigit(cp) && !isCjk(cp))) continue;
            total++;
            if (isCjk(cp)) cjk++;
        }
        if (total < 2 || total > 6 || cjk < 2 || cjk * 2 < total) return false;
        Rect bounds = documentBounds(document);
        if (bounds.isEmpty()) return false;
        float density = ScreenGeometry.density(app);
        return bounds.width() <= Math.round(220f * density)
                && bounds.height() <= Math.round(96f * density);
    }

    private static boolean verificationLengthCompatible(OcrDocument cached, OcrDocument local) {
        if (!usable(local)) return false;
        if (!usable(cached)) return true;
        int cachedCount = recognitionCodePointCount(cached.fullText());
        int localCount = recognitionCodePointCount(local.fullText());
        return cachedCount <= 0 || localCount == cachedCount;
    }

    private static int recognitionCodePointCount(String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        for (int cp : text.codePoints().toArray()) {
            if (Character.isLetterOrDigit(cp) || isCjk(cp)) count++;
        }
        return count;
    }

    private static Rect documentBounds(OcrDocument document) {
        Rect out = null;
        if (document != null) {
            for (OcrDocument.CharUnit c : document.chars()) {
                if (c == null || c.bounds().isEmpty()) continue;
                Rect r = c.bounds();
                if (out == null) out = new Rect(r); else out.union(r);
            }
        }
        return out == null ? new Rect() : out;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static void shiftIntoBounds(Rect rect, Rect bounds) {
        if (rect == null || bounds == null || rect.isEmpty() || bounds.isEmpty()) return;
        if (rect.width() >= bounds.width()) {
            rect.left = bounds.left;
            rect.right = bounds.right;
        } else {
            if (rect.left < bounds.left) rect.offset(bounds.left - rect.left, 0);
            if (rect.right > bounds.right) rect.offset(bounds.right - rect.right, 0);
        }
        if (rect.height() >= bounds.height()) {
            rect.top = bounds.top;
            rect.bottom = bounds.bottom;
        } else {
            if (rect.top < bounds.top) rect.offset(0, bounds.top - rect.top);
            if (rect.bottom > bounds.bottom) rect.offset(0, bounds.bottom - rect.bottom);
        }
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

    private static float dp(Context app, float value) {
        return value * ScreenGeometry.density(app);
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
