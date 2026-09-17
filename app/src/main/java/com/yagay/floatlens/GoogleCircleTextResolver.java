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
 * TextMap + lazy-recognition resolver for the Google-style Circle workspace.
 *
 * <p>The frozen screenshot is the only content source. Background work performs detection only on a
 * downscaled copy, then geometry stitches detector boxes into paragraph-like TextMap nodes. No
 * background OCR is performed. TextMap decides only which local reading-flow context ROI is worth
 * recognizing; the user's actual gesture always decides the initial text selection. If detection is
 * not ready, misses, or a context OCR succeeds but cannot map the gesture to selectable text, a
 * tight gesture crop is OCR'd directly. Successful region OCR is cached only for the lifetime of the
 * frozen frame.</p>
 */
final class GoogleCircleTextResolver {
    enum Source { IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        /** SCREEN-space OCR document for the lazily recognized context ROI. */
        final OcrDocument document;
        /** Gesture-scoped subset used only to initialize the selection range. */
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

    private static final class CachedRegion {
        final Rect bitmapRoi;
        final OcrDocument screenDocument;

        CachedRegion(Rect bitmapRoi, OcrDocument screenDocument) {
            this.bitmapRoi = new Rect(bitmapRoi);
            this.screenDocument = screenDocument;
        }
    }

    private static final class PreloadState {
        final long generation;
        final Context app;
        final WeakReference<GoogleCircleCapture.Frame> frameRef;
        final ArrayList<CachedRegion> regionCache = new ArrayList<>();

        CircleTextMap textMap;
        Throwable detectionError;
        boolean detectionDone;
        int detectorModel;
        long detectionMs;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }
    }

    private static final ExecutorService LAYOUT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-text-map");
        t.setDaemon(true);
        return t;
    });

    private static final Object STATE_LOCK = new Object();
    private static final int DETECTOR_MAX_LONG_SIDE = 1024;
    private static final int REGION_CACHE_MAX = 12;
    private static final float REGION_CACHE_DESIRED_COVERAGE_MIN = 0.96f;
    private static final float REGION_CACHE_MIN_AREA_RATIO = 0.96f;
    private static final float REGION_CACHE_GESTURE_COVERAGE_MIN = 0.98f;
    private static final float TEXT_MAP_HIT_PAD_DP = 14f;
    private static final float TEXT_MAP_ROI_PAD_X_DP = 14f;
    private static final float TEXT_MAP_ROI_PAD_Y_DP = 10f;
    private static final float CACHED_OCR_TAP_TOLERANCE_DP = 10f;
    private static final float RANGE_CORRIDOR_DP = 10f;
    private static final float LOCAL_TAP_HALF_WIDTH_DP = 92f;
    private static final float LOCAL_TAP_HALF_HEIGHT_DP = 42f;
    private static final float LOCAL_RANGE_PAD_X_DP = 18f;
    private static final float LOCAL_RANGE_PAD_Y_DP = 16f;
    private static final float LOCAL_MIN_WIDTH_DP = 96f;
    private static final float LOCAL_MIN_HEIGHT_DP = 44f;

    private static long generation;
    private static PreloadState current;

    static void preload(Context context, GoogleCircleCapture.Frame frame) {
        if (context == null || frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) return;
        Context app = context.getApplicationContext();
        PreloadState state;
        synchronized (STATE_LOCK) {
            if (sameFrame(current, frame)) return;
            if (current != null) clearLocked(current);
            state = new PreloadState(++generation, app, frame);
            current = state;
        }

        DiagnosticLog.i(app, "G_CIRCLE_TEXT_MAP", "start generation=" + state.generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " strategy=detector_textmap_then_lazy_ocr"
                + " background=det_only_downscaled"
                + " paragraphStitching=geometry_local_reading_flow"
                + " lazyRecognition=true regionCache=frozen_frame_strict_coverage"
                + " backgroundOcr=false tileOcr=false fullFrameOcr=false"
                + " preindexBlocking=false viewText=false coordinateSpace=BITMAP");

        LAYOUT_IO.execute(() -> prepareTextMap(state, frame));
    }

    static void resolve(Context context, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (context == null || frame == null || gesture == null || callback == null) return;
        Context app = context.getApplicationContext();
        preload(app, frame);

        PreloadState state;
        CircleTextMap map;
        Throwable detectionError;
        boolean detectionDone;
        synchronized (STATE_LOCK) {
            state = sameFrame(current, frame) ? current : null;
            if (state == null) {
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreenBounds(frame, gesture), new Rect(),
                        new IllegalStateException("TextMap state unavailable"), true));
                return;
            }
            map = state.textMap;
            detectionError = state.detectionError;
            detectionDone = state.detectionDone;
        }

        int hitPad = bitmapPxForDp(state.app, frame, TEXT_MAP_HIT_PAD_DP);
        CircleTextMap.Target target = map == null ? null : map.targetFor(gesture, hitPad);
        boolean textMapTarget = target != null && !target.bounds.isEmpty();
        Rect desiredRoi;
        if (textMapTarget) {
            desiredRoi = expandTextMapRoi(state.app, frame, target.bounds);
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_MAP", "hit gesture=" + gesture.kind
                    + " paragraphs=" + target.idsForLog()
                    + " localFlowLines=" + target.lineCount
                    + " roi=" + desiredRoi.toShortString()
                    + " context=gesture_anchored_local_reading_flow"
                    + " lazyRecognition=true selection=gesture_scoped");
        } else {
            desiredRoi = localBitmapRoi(state.app, frame, gesture);
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_MAP", "miss gesture=" + gesture.kind
                    + " mapReady=" + (map != null)
                    + " detectionDone=" + detectionDone
                    + " -> gesture_roi_fallback"
                    + (detectionError == null ? "" : " detectionError=" + safe(detectionError))
                    + " roi=" + desiredRoi.toShortString());
        }

        CachedRegion cached = findCachedRegion(state, desiredRoi, frame, gesture);
        if (cached != null) {
            OcrDocument initial = initialSelection(state.app, frame, gesture, cached.screenDocument);
            if (usable(initial)) {
                DiagnosticLog.i(app, "G_CIRCLE_LAZY_OCR", "cache hit gesture=" + gesture.kind
                        + " source=" + (textMapTarget ? "textmap_context" : "gesture_fallback")
                        + " desiredRoi=" + desiredRoi.toShortString()
                        + " cachedRoi=" + cached.bitmapRoi.toShortString()
                        + " chars=" + cached.screenDocument.chars().size()
                        + " selectedChars=" + initial.chars().size()
                        + " strictCoverage=true selection=gesture_scoped");
                callback.onResolved(new Result(Source.IMAGE_OCR, cached.screenDocument, initial,
                        gestureScreenBounds(frame, gesture), cached.bitmapRoi, null,
                        !textMapTarget));
                return;
            }
            if (textMapTarget && tryTightFallback(state, frame, gesture, desiredRoi,
                    "cache_selection_miss", callback)) {
                return;
            }
        }

        resolveLazyScreenshot(state, frame, gesture, desiredRoi, textMapTarget, callback);
    }

    static void release(Context context, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = context == null ? null : context.getApplicationContext();
        PreloadState released;
        int regions;
        int paragraphs;
        int lines;
        synchronized (STATE_LOCK) {
            if (!sameFrame(current, frame)) return;
            released = current;
            regions = released.regionCache.size();
            paragraphs = released.textMap == null ? 0 : released.textMap.paragraphCount();
            lines = released.textMap == null ? 0 : released.textMap.lineCount();
            current = null;
            generation++;
            clearLocked(released);
        }
        DiagnosticLog.i(app == null ? released.app : app, "G_CIRCLE_TEXT_MAP",
                "release generation=" + released.generation
                        + " reason=" + (reason == null ? "unknown" : reason)
                        + " paragraphs=" + paragraphs + " lines=" + lines
                        + " cachedRegions=" + regions);
    }

    private static void prepareTextMap(PreloadState state, GoogleCircleCapture.Frame frame) {
        if (!isCurrent(state, frame)) return;
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) return;

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int longest = Math.max(sourceWidth, sourceHeight);
        float scale = longest > DETECTOR_MAX_LONG_SIDE
                ? DETECTOR_MAX_LONG_SIDE / (float) longest : 1f;
        int detectorWidth = Math.max(1, Math.round(sourceWidth * scale));
        int detectorHeight = Math.max(1, Math.round(sourceHeight * scale));

        final Bitmap detectorBitmap;
        try {
            detectorBitmap = scale < 0.999f
                    ? Bitmap.createScaledBitmap(source, detectorWidth, detectorHeight, true)
                    : source.copy(Bitmap.Config.ARGB_8888, false);
            if (detectorBitmap == null) throw new IllegalStateException("detector bitmap copy failed");
        } catch (Throwable t) {
            finishTextMapFailure(state, frame, t);
            return;
        }

        if (!isCurrent(state, frame)) {
            recycle(detectorBitmap);
            return;
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_MAP", "detect begin generation="
                + state.generation
                + " source=" + sourceWidth + "x" + sourceHeight
                + " detectorBitmap=" + detectorWidth + "x" + detectorHeight
                + " scale=" + String.format(java.util.Locale.US, "%.3f", scale)
                + " recognition=false");

        PaddleTextDetectorBridge.detect(state.app, detectorBitmap,
                new PaddleTextDetectorBridge.Callback() {
                    @Override public void onSuccess(List<Rect> regions, long totalMs,
                                                    int model, long coldLoadMs) {
                        try {
                            if (!isCurrent(state, frame)) return;
                            ArrayList<Rect> original = mapRegionsToOriginal(regions,
                                    detectorBitmap.getWidth(), detectorBitmap.getHeight(),
                                    sourceWidth, sourceHeight);
                            CircleTextMap map = CircleTextMap.build(original, sourceWidth, sourceHeight);
                            synchronized (STATE_LOCK) {
                                if (current != state) return;
                                state.textMap = map;
                                state.detectionDone = true;
                                state.detectionError = null;
                                state.detectorModel = model;
                                state.detectionMs = totalMs;
                            }
                            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_MAP", "ready generation="
                                    + state.generation
                                    + " model=" + model
                                    + " detectorRegions=" + original.size()
                                    + " lines=" + map.lineCount()
                                    + " paragraphs=" + map.paragraphCount()
                                    + " detectorMs=" + totalMs
                                    + " coldLoadMs=" + coldLoadMs
                                    + " recognition=false");
                        } catch (Throwable t) {
                            finishTextMapFailure(state, frame, t);
                        } finally {
                            recycle(detectorBitmap);
                        }
                    }

                    @Override public void onFailure(String message) {
                        try {
                            if (isCurrent(state, frame)) {
                                finishTextMapFailure(state, frame, new IllegalStateException(message));
                            }
                        } finally {
                            recycle(detectorBitmap);
                        }
                    }
                });
    }

    private static void finishTextMapFailure(PreloadState state,
                                             GoogleCircleCapture.Frame frame,
                                             Throwable error) {
        synchronized (STATE_LOCK) {
            if (current != state) return;
            state.detectionDone = true;
            state.detectionError = error;
            state.textMap = null;
        }
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_MAP", "failed generation=" + state.generation
                + " error=" + safe(error) + " fallback=gesture_local_ocr");
    }

    private static ArrayList<Rect> mapRegionsToOriginal(List<Rect> source,
                                                         int detectorWidth, int detectorHeight,
                                                         int originalWidth, int originalHeight) {
        ArrayList<Rect> out = new ArrayList<>();
        if (source == null || detectorWidth <= 0 || detectorHeight <= 0) return out;
        float sx = originalWidth / (float) detectorWidth;
        float sy = originalHeight / (float) detectorHeight;
        for (Rect rect : source) {
            if (rect == null || rect.isEmpty()) continue;
            int left = Math.max(0, Math.min(originalWidth - 1, Math.round(rect.left * sx)));
            int top = Math.max(0, Math.min(originalHeight - 1, Math.round(rect.top * sy)));
            int right = Math.max(left + 1, Math.min(originalWidth, Math.round(rect.right * sx)));
            int bottom = Math.max(top + 1, Math.min(originalHeight, Math.round(rect.bottom * sy)));
            out.add(new Rect(left, top, right, bottom));
        }
        return out;
    }

    private static void resolveLazyScreenshot(PreloadState state,
                                              GoogleCircleCapture.Frame frame,
                                              GoogleCircleSelection.Selection gesture,
                                              Rect bitmapRoi,
                                              boolean textMapTarget,
                                              Callback callback) {
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        if (!isCurrent(state, frame) || bitmapRoi == null || bitmapRoi.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, null, gestureScreen,
                    bitmapRoi, new IllegalStateException("empty lazy OCR ROI"), !textMapTarget));
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(frame.bitmap, bitmapRoi.left, bitmapRoi.top,
                    bitmapRoi.width(), bitmapRoi.height());
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreen, bitmapRoi, t, !textMapTarget));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_LAZY_OCR", "start gesture=" + gesture.kind
                + " source=" + (textMapTarget ? "textmap_context" : "gesture_fallback")
                + " roi=" + bitmapRoi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " engine=direct_mlkit backgroundOcr=false");

        CircleStableOcr.recognizeMlKit(state.app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument localBitmap) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument parentBitmap = localBitmap == null ? null
                            : localBitmap.translated(bitmapRoi.left, bitmapRoi.top,
                                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
                    OcrDocument localScreen = parentBitmap == null ? null
                            : frame.transform.documentBitmapToScreen(parentBitmap);

                    if (!usable(localScreen)) {
                        if (textMapTarget && tryTightFallback(state, frame, gesture, bitmapRoi,
                                "context_document_empty", callback)) {
                            return;
                        }
                        callback.onResolved(new Result(Source.NONE, null, null,
                                gestureScreen, bitmapRoi,
                                new IllegalStateException("lazy OCR has no selectable text"),
                                !textMapTarget));
                        return;
                    }

                    OcrDocument initial = initialSelection(state.app, frame, gesture, localScreen);
                    if (!usable(initial)) {
                        // Keep the useful context OCR for future nearby gestures even when this
                        // gesture cannot be mapped into it. The current gesture gets one tight,
                        // independent OCR attempt instead of reporting a false miss immediately.
                        cacheRegion(state, bitmapRoi, localScreen);
                        if (textMapTarget && tryTightFallback(state, frame, gesture, bitmapRoi,
                                "context_selection_miss", callback)) {
                            return;
                        }
                        callback.onResolved(new Result(Source.NONE, null, null,
                                gestureScreen, bitmapRoi,
                                new IllegalStateException("lazy OCR has no selectable text"),
                                !textMapTarget));
                        return;
                    }

                    cacheRegion(state, bitmapRoi, localScreen);
                    DiagnosticLog.i(state.app, "G_CIRCLE_LAZY_OCR", "success gesture="
                            + gesture.kind
                            + " source=" + (textMapTarget ? "textmap_context" : "gesture_fallback")
                            + " engine=" + localBitmap.engine()
                            + " documentChars=" + localScreen.chars().size()
                            + " selectedChars=" + initial.chars().size()
                            + " selection=gesture_scoped"
                            + " cacheStore=true elapsedMs="
                            + (android.os.SystemClock.uptimeMillis() - started));
                    callback.onResolved(new Result(Source.IMAGE_OCR, localScreen, initial,
                            gestureScreen, bitmapRoi, null, !textMapTarget));
                } catch (Throwable t) {
                    callback.onResolved(new Result(Source.NONE, null, null,
                            gestureScreen, bitmapRoi, t, !textMapTarget));
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                if (textMapTarget && tryTightFallback(state, frame, gesture, bitmapRoi,
                        "context_ocr_failed", callback)) {
                    return;
                }
                DiagnosticLog.i(state.app, "G_CIRCLE_LAZY_OCR", "failed gesture="
                        + gesture.kind + " error=" + safe(error)
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreen, bitmapRoi, error, !textMapTarget));
            }
        });
    }

    private static boolean tryTightFallback(PreloadState state,
                                            GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture,
                                            Rect contextRoi,
                                            String reason,
                                            Callback callback) {
        if (!isCurrent(state, frame)) return false;
        Rect tightRoi = localBitmapRoi(state.app, frame, gesture);
        if (tightRoi.isEmpty() || tightRoi.equals(contextRoi)) return false;

        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_FALLBACK", "start gesture=" + gesture.kind
                + " reason=" + reason
                + " contextRoi=" + (contextRoi == null ? "[]" : contextRoi.toShortString())
                + " tightRoi=" + tightRoi.toShortString()
                + " engine=direct_mlkit");
        resolveLazyScreenshot(state, frame, gesture, tightRoi, false, result -> {
            if (result != null && result.source == Source.IMAGE_OCR
                    && usable(result.initialSelectionDocument)) {
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_FALLBACK", "success gesture="
                        + gesture.kind + " reason=" + reason
                        + " selectedChars=" + result.initialSelectionDocument.chars().size()
                        + " roi=" + tightRoi.toShortString());
            } else {
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_FALLBACK", "failed gesture="
                        + gesture.kind + " reason=" + reason
                        + " error=" + (result == null || result.error == null
                        ? "no_selectable_text" : safe(result.error)));
            }
            callback.onResolved(result);
        });
        return true;
    }

    private static OcrDocument initialSelection(Context app, GoogleCircleCapture.Frame frame,
                                                GoogleCircleSelection.Selection gesture,
                                                OcrDocument document) {
        if (!usable(document)) return null;

        OcrDocument initial = selectGesture(app, frame, gesture, document);
        if (!usable(initial) && gesture.kind == GoogleCircleSelection.Kind.TAP) {
            initial = nearestGroupDocument(document,
                    frame.bitmapPointToScreen(gesture.focus.x, gesture.focus.y));
        }
        return initial;
    }

    private static CachedRegion findCachedRegion(PreloadState state, Rect desiredRoi,
                                                 GoogleCircleCapture.Frame frame,
                                                 GoogleCircleSelection.Selection gesture) {
        if (state == null || desiredRoi == null || desiredRoi.isEmpty()) return null;
        Rect gestureBitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        int focusX = Math.round(gesture.focus.x);
        int focusY = Math.round(gesture.focus.y);
        synchronized (STATE_LOCK) {
            if (current != state) return null;
            for (int i = state.regionCache.size() - 1; i >= 0; i--) {
                CachedRegion entry = state.regionCache.get(i);
                Rect cached = entry.bitmapRoi;
                float desiredCoverage = overlapCoverage(desiredRoi, cached);
                float areaRatio = rectArea(desiredRoi) <= 0L ? 0f
                        : Math.min(1f, rectArea(cached) / (float) rectArea(desiredRoi));
                boolean desiredCovered = cached.contains(desiredRoi)
                        || (desiredCoverage >= REGION_CACHE_DESIRED_COVERAGE_MIN
                        && areaRatio >= REGION_CACHE_MIN_AREA_RATIO);
                boolean gestureCovered = gesture.kind == GoogleCircleSelection.Kind.TAP
                        ? cached.contains(focusX, focusY)
                        : overlapCoverage(gestureBitmap, cached)
                        >= REGION_CACHE_GESTURE_COVERAGE_MIN;
                if (desiredCovered && gestureCovered && usable(entry.screenDocument)) {
                    state.regionCache.remove(i);
                    state.regionCache.add(entry);
                    return entry;
                }
            }
        }
        return null;
    }

    private static void cacheRegion(PreloadState state, Rect bitmapRoi, OcrDocument document) {
        if (state == null || bitmapRoi == null || bitmapRoi.isEmpty() || !usable(document)) return;
        synchronized (STATE_LOCK) {
            if (current != state) return;
            for (int i = state.regionCache.size() - 1; i >= 0; i--) {
                CachedRegion existing = state.regionCache.get(i);
                if (existing.bitmapRoi.equals(bitmapRoi)
                        || overlapCoverage(bitmapRoi, existing.bitmapRoi) >= 0.90f) {
                    state.regionCache.remove(i);
                }
            }
            state.regionCache.add(new CachedRegion(bitmapRoi, document));
            while (state.regionCache.size() > REGION_CACHE_MAX) state.regionCache.remove(0);
        }
    }

    private static Rect expandTextMapRoi(Context app, GoogleCircleCapture.Frame frame, Rect source) {
        int padX = bitmapPxForDp(app, frame, TEXT_MAP_ROI_PAD_X_DP);
        int padY = bitmapPxForDp(app, frame, TEXT_MAP_ROI_PAD_Y_DP);
        return clampRect(new Rect(source.left - padX, source.top - padY,
                        source.right + padX, source.bottom + padY),
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
    }

    private static OcrDocument selectGesture(Context app, GoogleCircleCapture.Frame frame,
                                             GoogleCircleSelection.Selection gesture,
                                             OcrDocument document) {
        if (!usable(document)) return null;
        return CircleGestureTextSelector.selectDocument(app, frame, gesture, document,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                "lazy-screenshot-selected");
    }

    private static Rect localBitmapRoi(Context app, GoogleCircleCapture.Frame frame,
                                       GoogleCircleSelection.Selection gesture) {
        int width = frame.bitmap.getWidth();
        int height = frame.bitmap.getHeight();
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
        Rect expanded = clampRect(new Rect(base.left - padX, base.top - padY,
                base.right + padX, base.bottom + padY), width, height);
        int minW = bitmapPxForDp(app, frame, LOCAL_MIN_WIDTH_DP);
        int minH = bitmapPxForDp(app, frame, LOCAL_MIN_HEIGHT_DP);
        return ensureMinimumRect(expanded, width, height, minW, minH);
    }

    private static Rect ensureMinimumRect(Rect source, int width, int height, int minW, int minH) {
        if (source == null || source.isEmpty()) return new Rect();
        int targetW = Math.min(width, Math.max(source.width(), Math.max(1, minW)));
        int targetH = Math.min(height, Math.max(source.height(), Math.max(1, minH)));
        int left = Math.max(0, Math.min(source.centerX() - targetW / 2, width - targetW));
        int top = Math.max(0, Math.min(source.centerY() - targetH / 2, height - targetH));
        return new Rect(left, top, left + targetW, top + targetH);
    }

    private static Rect clampRect(Rect source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return new Rect();
        int left = Math.max(0, Math.min(width - 1, source.left));
        int top = Math.max(0, Math.min(height - 1, source.top));
        int right = Math.max(left + 1, Math.min(width, source.right));
        int bottom = Math.max(top + 1, Math.min(height, source.bottom));
        return new Rect(left, top, right, bottom);
    }

    private static int bitmapPxForDp(Context app, GoogleCircleCapture.Frame frame, float dp) {
        float density = app.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(frame.transform.screenDistanceToBitmap(
                Math.max(1f, dp * density))));
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
        OcrDocument.Line line = new OcrDocument.Line(value, bounds, document.confidence(), chars);
        return OcrDocument.screenSpace(value, List.of(value), List.of(line),
                document.engine() + "+nearest-group", document.confidence(), document.score(),
                document.imageWidth(), document.imageHeight());
    }

    private static float overlapCoverage(Rect target, Rect cover) {
        if (target == null || cover == null || target.isEmpty() || cover.isEmpty()) return 0f;
        int left = Math.max(target.left, cover.left);
        int top = Math.max(target.top, cover.top);
        int right = Math.min(target.right, cover.right);
        int bottom = Math.min(target.bottom, cover.bottom);
        if (right <= left || bottom <= top) return 0f;
        long intersection = (long) (right - left) * (bottom - top);
        long area = rectArea(target);
        return area <= 0L ? 0f : Math.min(1f, intersection / (float) area);
    }

    private static long rectArea(Rect rect) {
        return rect == null ? 0L
                : (long) Math.max(0, rect.width()) * Math.max(0, rect.height());
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isScreenSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static boolean sameFrame(PreloadState state, GoogleCircleCapture.Frame frame) {
        return state != null && state.frameRef.get() == frame;
    }

    private static boolean isCurrent(PreloadState state, GoogleCircleCapture.Frame frame) {
        synchronized (STATE_LOCK) {
            return current == state && sameFrame(state, frame);
        }
    }

    private static void clearLocked(PreloadState state) {
        state.textMap = null;
        state.detectionError = null;
        state.detectionDone = false;
        state.regionCache.clear();
    }

    private static Rect gestureScreenBounds(GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        Rect bitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
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
