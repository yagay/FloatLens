package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Full-screen lazy OCR resolver for the Google-style Circle workspace.
 *
 * <p>The frozen screenshot is the only content source. The first text gesture recognizes the whole
 * frozen frame once with the OCR engine selected in Settings, caches that SCREEN-space document for
 * the workspace lifetime, and every later tap/highlight/scribble selects from the same complete
 * document. While that first full-screen OCR is still running, later gestures are coalesced into one
 * latest pending gesture instead of starting duplicate full-screen OCR requests. Gesture geometry
 * only decides the selection; it never shrinks the primary OCR range. A tight local OCR remains only
 * as a last-resort fallback when a full-screen OCR document exists but the gesture still cannot map
 * to selectable text.</p>
 */
final class GoogleCircleTextResolver {
    enum Source { IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        /** SCREEN-space OCR document for the complete frozen screen (or fallback ROI). */
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

    private static final class PendingResolve {
        final GoogleCircleSelection.Selection gesture;
        final Callback callback;
        final boolean replacedOlderPending;

        PendingResolve(GoogleCircleSelection.Selection gesture, Callback callback,
                       boolean replacedOlderPending) {
            this.gesture = gesture;
            this.callback = callback;
            this.replacedOlderPending = replacedOlderPending;
        }
    }

    private static final class PreloadState {
        final long generation;
        final Context app;
        final WeakReference<GoogleCircleCapture.Frame> frameRef;
        final ArrayList<CachedRegion> regionCache = new ArrayList<>();
        boolean fullScreenOcrInFlight;
        GoogleCircleSelection.Selection pendingGesture;
        Callback pendingCallback;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }
    }

    private static final Object STATE_LOCK = new Object();
    private static final int REGION_CACHE_MAX = 3;
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
        synchronized (STATE_LOCK) {
            if (sameFrame(current, frame)) return;
            if (current != null) clearLocked(current);
            current = new PreloadState(++generation, app, frame);
        }

        DiagnosticLog.i(app, "G_CIRCLE_FULL_OCR", "ready generation=" + generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " strategy=lazy_full_screen_single_flight_then_cache"
                + " primaryOcrRange=entire_frozen_frame"
                + " selection=precise_gesture"
                + " pendingGesturePolicy=latest_wins"
                + " detector=false textMap=false tileOcr=false viewText=false"
                + " backgroundOcr=false coordinateSpace=BITMAP");
    }

    static void resolve(Context context, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (context == null || frame == null || gesture == null || callback == null) return;
        Context app = context.getApplicationContext();
        preload(app, frame);

        PreloadState state;
        synchronized (STATE_LOCK) {
            state = sameFrame(current, frame) ? current : null;
        }
        if (state == null) {
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreenBounds(frame, gesture), new Rect(),
                    new IllegalStateException("full-screen OCR state unavailable"), false));
            return;
        }

        Rect fullRoi = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
        CachedRegion cached = findCachedRegion(state, fullRoi);
        if (cached != null) {
            OcrDocument initial = initialSelection(state.app, frame, gesture, cached.screenDocument);
            if (usable(initial)) {
                DiagnosticLog.i(app, "G_CIRCLE_FULL_OCR", "cache hit gesture=" + gesture.kind
                        + " roi=" + cached.bitmapRoi.toShortString()
                        + " documentChars=" + cached.screenDocument.chars().size()
                        + " selectedChars=" + initial.chars().size()
                        + " selection=precise_gesture fullScreen=true");
                callback.onResolved(new Result(Source.IMAGE_OCR, cached.screenDocument, initial,
                        gestureScreenBounds(frame, gesture), cached.bitmapRoi, null, false));
                return;
            }

            if (tryTightFallback(state, frame, gesture, fullRoi,
                    "full_screen_cache_selection_miss", callback)) {
                return;
            }
        }

        boolean coalesced = false;
        boolean replaced = false;
        GoogleCircleSelection.Kind replacedKind = null;
        synchronized (STATE_LOCK) {
            if (current != state || !sameFrame(state, frame)) {
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreenBounds(frame, gesture), new Rect(),
                        new IllegalStateException("full-screen OCR state changed"), false));
                return;
            }
            if (state.fullScreenOcrInFlight) {
                coalesced = true;
                replaced = state.pendingGesture != null;
                replacedKind = replaced ? state.pendingGesture.kind : null;
                state.pendingGesture = gesture;
                state.pendingCallback = callback;
            } else {
                state.fullScreenOcrInFlight = true;
                state.pendingGesture = null;
                state.pendingCallback = null;
            }
        }

        if (coalesced) {
            DiagnosticLog.i(app, "G_CIRCLE_FULL_OCR", "coalesced gesture=" + gesture.kind
                    + " policy=latest_wins fullScreenOcrInFlight=true"
                    + " replacedPending=" + replaced
                    + (replacedKind == null ? "" : " replacedKind=" + replacedKind)
                    + " newBitmapCopy=false newOcrRequest=false");
            return;
        }

        DiagnosticLog.i(app, "G_CIRCLE_FULL_OCR", "start gesture=" + gesture.kind
                + " roi=" + fullRoi.toShortString()
                + " crop=" + fullRoi.width() + "x" + fullRoi.height()
                + " primaryOcrRange=entire_frozen_frame"
                + " singleFlight=leader pendingGesturePolicy=latest_wins"
                + " selection=precise_gesture");
        resolveScreenshot(state, frame, gesture, fullRoi, true, callback);
    }

    static void release(Context context, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = context == null ? null : context.getApplicationContext();
        PreloadState released;
        int regions;
        boolean inFlight;
        boolean hadPending;
        synchronized (STATE_LOCK) {
            if (!sameFrame(current, frame)) return;
            released = current;
            regions = released.regionCache.size();
            inFlight = released.fullScreenOcrInFlight;
            hadPending = released.pendingGesture != null;
            current = null;
            generation++;
            clearLocked(released);
        }
        DiagnosticLog.i(app == null ? released.app : app, "G_CIRCLE_FULL_OCR",
                "release generation=" + released.generation
                        + " reason=" + (reason == null ? "unknown" : reason)
                        + " fullScreen=true cachedRegions=" + regions
                        + " inFlight=" + inFlight + " hadPending=" + hadPending);
    }

    private static void resolveScreenshot(PreloadState state,
                                          GoogleCircleCapture.Frame frame,
                                          GoogleCircleSelection.Selection gesture,
                                          Rect bitmapRoi,
                                          boolean fullScreen,
                                          Callback callback) {
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        if (!isCurrent(state, frame) || bitmapRoi == null || bitmapRoi.isEmpty()) {
            PendingResolve delivery = fullScreen
                    ? finishFullScreenFlight(state, gesture, callback)
                    : new PendingResolve(gesture, callback, false);
            delivery.callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreenBounds(frame, delivery.gesture), bitmapRoi,
                    new IllegalStateException("empty OCR ROI"), !fullScreen));
            return;
        }

        final Bitmap crop;
        try {
            if (bitmapRoi.left == 0 && bitmapRoi.top == 0
                    && bitmapRoi.right == frame.bitmap.getWidth()
                    && bitmapRoi.bottom == frame.bitmap.getHeight()) {
                crop = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
            } else {
                crop = Bitmap.createBitmap(frame.bitmap, bitmapRoi.left, bitmapRoi.top,
                        bitmapRoi.width(), bitmapRoi.height());
            }
            if (crop == null) throw new IllegalStateException("OCR bitmap copy failed");
        } catch (Throwable t) {
            PendingResolve delivery = fullScreen
                    ? finishFullScreenFlight(state, gesture, callback)
                    : new PendingResolve(gesture, callback, false);
            delivery.callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreenBounds(frame, delivery.gesture), bitmapRoi, t, !fullScreen));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, fullScreen ? "G_CIRCLE_FULL_OCR" : "G_CIRCLE_LOCAL_FALLBACK",
                "recognize gesture=" + gesture.kind
                        + " source=" + (fullScreen ? "full_screen" : "gesture_fallback")
                        + " roi=" + bitmapRoi.toShortString()
                        + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                        + " engine=settings_selected"
                        + (fullScreen ? " singleFlight=leader" : ""));

        CircleStableOcr.recognizeConfigured(state.app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument localBitmap) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument parentBitmap = localBitmap == null ? null
                            : localBitmap.translated(bitmapRoi.left, bitmapRoi.top,
                                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
                    OcrDocument screenDocument = parentBitmap == null ? null
                            : frame.transform.documentBitmapToScreen(parentBitmap);

                    if (fullScreen && usable(screenDocument)) {
                        cacheRegion(state, bitmapRoi, screenDocument);
                    }

                    PendingResolve delivery = fullScreen
                            ? finishFullScreenFlight(state, gesture, callback)
                            : new PendingResolve(gesture, callback, false);
                    GoogleCircleSelection.Selection deliveryGesture = delivery.gesture;
                    Callback deliveryCallback = delivery.callback;
                    Rect deliveryGestureScreen = gestureScreenBounds(frame, deliveryGesture);

                    if (fullScreen) {
                        DiagnosticLog.i(state.app, "G_CIRCLE_FULL_OCR",
                                "single flight complete leaderGesture=" + gesture.kind
                                        + " deliveryGesture=" + deliveryGesture.kind
                                        + " deliveredLatest=" + (deliveryGesture != gesture)
                                        + " replacedOlderPending=" + delivery.replacedOlderPending
                                        + " pendingCleared=true");
                    }

                    if (!usable(screenDocument)) {
                        if (fullScreen && tryTightFallback(state, frame, deliveryGesture, bitmapRoi,
                                "full_screen_document_empty", deliveryCallback)) {
                            return;
                        }
                        deliveryCallback.onResolved(new Result(Source.NONE, null, null,
                                deliveryGestureScreen, bitmapRoi,
                                new IllegalStateException("OCR has no selectable text"),
                                !fullScreen));
                        return;
                    }

                    OcrDocument initial = initialSelection(state.app, frame,
                            deliveryGesture, screenDocument);
                    if (!usable(initial)) {
                        if (fullScreen && tryTightFallback(state, frame, deliveryGesture, bitmapRoi,
                                "full_screen_selection_miss", deliveryCallback)) {
                            return;
                        }
                        deliveryCallback.onResolved(new Result(Source.NONE, null, null,
                                deliveryGestureScreen, bitmapRoi,
                                new IllegalStateException("OCR has no gesture-scoped selection"),
                                !fullScreen));
                        return;
                    }

                    if (!fullScreen) cacheRegion(state, bitmapRoi, screenDocument);
                    DiagnosticLog.i(state.app,
                            fullScreen ? "G_CIRCLE_FULL_OCR" : "G_CIRCLE_LOCAL_FALLBACK",
                            "success gesture=" + deliveryGesture.kind
                                    + " source=" + (fullScreen ? "full_screen" : "gesture_fallback")
                                    + " engine=" + localBitmap.engine()
                                    + " documentChars=" + screenDocument.chars().size()
                                    + " selectedChars=" + initial.chars().size()
                                    + " selection=precise_gesture"
                                    + " cacheStore=true elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    deliveryCallback.onResolved(new Result(Source.IMAGE_OCR, screenDocument, initial,
                            deliveryGestureScreen, bitmapRoi, null, !fullScreen));
                } catch (Throwable t) {
                    PendingResolve delivery = fullScreen
                            ? finishFullScreenFlight(state, gesture, callback)
                            : new PendingResolve(gesture, callback, false);
                    delivery.callback.onResolved(new Result(Source.NONE, null, null,
                            gestureScreenBounds(frame, delivery.gesture), bitmapRoi, t, !fullScreen));
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                PendingResolve delivery = fullScreen
                        ? finishFullScreenFlight(state, gesture, callback)
                        : new PendingResolve(gesture, callback, false);
                GoogleCircleSelection.Selection deliveryGesture = delivery.gesture;
                Callback deliveryCallback = delivery.callback;
                Rect deliveryGestureScreen = gestureScreenBounds(frame, deliveryGesture);

                if (fullScreen && tryTightFallback(state, frame, deliveryGesture, bitmapRoi,
                        "full_screen_ocr_failed", deliveryCallback)) {
                    return;
                }
                DiagnosticLog.i(state.app,
                        fullScreen ? "G_CIRCLE_FULL_OCR" : "G_CIRCLE_LOCAL_FALLBACK",
                        "failed gesture=" + deliveryGesture.kind
                                + " error=" + safe(error)
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                deliveryCallback.onResolved(new Result(Source.NONE, null, null,
                        deliveryGestureScreen, bitmapRoi, error, !fullScreen));
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
                + " fullScreenRoi=" + (contextRoi == null ? "[]" : contextRoi.toShortString())
                + " tightRoi=" + tightRoi.toShortString()
                + " engine=settings_selected");
        resolveScreenshot(state, frame, gesture, tightRoi, false, result -> {
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

    private static PendingResolve finishFullScreenFlight(PreloadState state,
                                                          GoogleCircleSelection.Selection originalGesture,
                                                          Callback originalCallback) {
        synchronized (STATE_LOCK) {
            if (current != state) {
                return new PendingResolve(originalGesture, originalCallback, false);
            }
            state.fullScreenOcrInFlight = false;
            boolean replacedOlderPending = false;
            if (state.pendingGesture != null && state.pendingCallback != null) {
                GoogleCircleSelection.Selection latestGesture = state.pendingGesture;
                Callback latestCallback = state.pendingCallback;
                state.pendingGesture = null;
                state.pendingCallback = null;
                return new PendingResolve(latestGesture, latestCallback, replacedOlderPending);
            }
            state.pendingGesture = null;
            state.pendingCallback = null;
            return new PendingResolve(originalGesture, originalCallback, false);
        }
    }

    private static OcrDocument initialSelection(Context app, GoogleCircleCapture.Frame frame,
                                                GoogleCircleSelection.Selection gesture,
                                                OcrDocument document) {
        if (!usable(document)) return null;
        return CircleGestureTextSelector.selectDocument(app, frame, gesture, document,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                "full-screen-gesture-selected");
    }

    private static CachedRegion findCachedRegion(PreloadState state, Rect desiredRoi) {
        if (state == null || desiredRoi == null || desiredRoi.isEmpty()) return null;
        synchronized (STATE_LOCK) {
            if (current != state) return null;
            for (int i = state.regionCache.size() - 1; i >= 0; i--) {
                CachedRegion entry = state.regionCache.get(i);
                if (entry.bitmapRoi.contains(desiredRoi) && usable(entry.screenDocument)) {
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
                if (state.regionCache.get(i).bitmapRoi.equals(bitmapRoi)) {
                    state.regionCache.remove(i);
                }
            }
            state.regionCache.add(new CachedRegion(bitmapRoi, document));
            while (state.regionCache.size() > REGION_CACHE_MAX) state.regionCache.remove(0);
        }
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
        state.regionCache.clear();
        state.fullScreenOcrInFlight = false;
        state.pendingGesture = null;
        state.pendingCallback = null;
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
