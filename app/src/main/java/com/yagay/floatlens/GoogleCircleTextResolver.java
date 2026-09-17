package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.lang.ref.WeakReference;

/**
 * Frozen-screen OCR resolver for Circle.
 *
 * <p>The selected full-screen OCR engine produces one cached SCREEN-space document. Selection
 * semantics are owned only by {@link CircleSelectionPlanner}; an optional PP-OCR correction engine
 * receives the planner's ROI and may refine text inside that plan, but it cannot reinterpret the
 * user's gesture into a different selection range.</p>
 */
final class GoogleCircleTextResolver {
    enum Source { IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        final OcrDocument document;
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
        OcrDocument fullScreenDocument;
        boolean fullScreenOcrInFlight;
        GoogleCircleSelection.Selection pendingGesture;
        Callback pendingCallback;
        boolean pendingReplaced;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }
    }

    private static final Object STATE_LOCK = new Object();
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
        PreloadState created;
        synchronized (STATE_LOCK) {
            if (sameFrame(current, frame)) return;
            if (current != null) clearLocked(current);
            created = new PreloadState(++generation, app, frame);
            current = created;
        }
        FloatSettings fs = new FloatSettings(app);
        DiagnosticLog.i(app, "G_CIRCLE_OCR", "ready generation=" + created.generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " fullEngine=" + CircleStableOcr.fullModeLabel(app)
                + " correctionEngine=" + CircleStableOcr.correctionModeLabel(app)
                + " fullMode=" + fs.circleFullOcrEngine()
                + " correctionMode=" + fs.circleCorrectionEngine()
                + " strategy=full_screen_once_plus_planned_correction"
                + " selectionOwner=CircleSelectionPlanner"
                + " correctionPolicy=preserve_selection_plan"
                + " pendingGesturePolicy=latest_wins"
                + " viewText=false coordinateSpace=SCREEN");
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
            callback.onResolved(failure(frame, gesture, new Rect(),
                    new IllegalStateException("Circle OCR state unavailable"), false));
            return;
        }

        OcrDocument cached;
        synchronized (STATE_LOCK) {
            cached = current == state ? state.fullScreenDocument : null;
        }
        if (usable(cached)) {
            resolveAgainstFullDocument(state, frame, gesture, cached, callback);
            return;
        }

        boolean coalesced;
        boolean replaced;
        synchronized (STATE_LOCK) {
            if (current != state || !sameFrame(state, frame)) {
                callback.onResolved(failure(frame, gesture, new Rect(),
                        new IllegalStateException("Circle OCR state changed"), false));
                return;
            }
            coalesced = state.fullScreenOcrInFlight;
            replaced = coalesced && state.pendingGesture != null;
            if (coalesced) {
                if (replaced) state.pendingReplaced = true;
                state.pendingGesture = gesture;
                state.pendingCallback = callback;
            } else {
                state.fullScreenOcrInFlight = true;
                state.pendingGesture = null;
                state.pendingCallback = null;
                state.pendingReplaced = false;
            }
        }

        if (coalesced) {
            DiagnosticLog.i(app, "G_CIRCLE_FULL_OCR", "coalesced gesture=" + gesture.kind
                    + " policy=latest_wins replacedPending=" + replaced
                    + " newBitmapCopy=false newOcrRequest=false");
            return;
        }
        startFullScreenOcr(state, frame, gesture, callback);
    }

    static void release(Context context, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = context == null ? null : context.getApplicationContext();
        PreloadState released;
        synchronized (STATE_LOCK) {
            if (!sameFrame(current, frame)) return;
            released = current;
            current = null;
            generation++;
            clearLocked(released);
        }
        DiagnosticLog.i(app == null ? released.app : app, "G_CIRCLE_OCR",
                "release generation=" + released.generation
                        + " reason=" + (reason == null ? "unknown" : reason));
    }

    private static void startFullScreenOcr(PreloadState state,
                                           GoogleCircleCapture.Frame frame,
                                           GoogleCircleSelection.Selection leaderGesture,
                                           Callback leaderCallback) {
        Rect fullRoi = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
        final Bitmap crop;
        try {
            crop = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (crop == null) throw new IllegalStateException("full-screen bitmap copy failed");
        } catch (Throwable t) {
            PendingResolve delivery = finishFullScreenFlight(state, leaderGesture, leaderCallback);
            delivery.callback.onResolved(failure(frame, delivery.gesture, fullRoi, t, false));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_FULL_OCR", "start gesture=" + leaderGesture.kind
                + " roi=" + fullRoi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " engine=" + CircleStableOcr.fullModeLabel(state.app)
                + " singleFlight=leader");

        CircleStableOcr.recognizeFullScreenSelected(state.app, crop,
                new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument bitmapDocument) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument screen = bitmapDocument == null ? null
                            : frame.transform.documentBitmapToScreen(bitmapDocument);
                    if (usable(screen)) {
                        synchronized (STATE_LOCK) {
                            if (current == state) state.fullScreenDocument = screen;
                        }
                    }
                    PendingResolve delivery = finishFullScreenFlight(
                            state, leaderGesture, leaderCallback);
                    DiagnosticLog.i(state.app, "G_CIRCLE_FULL_OCR",
                            "complete leaderGesture=" + leaderGesture.kind
                                    + " deliveryGesture=" + delivery.gesture.kind
                                    + " deliveredLatest=" + (delivery.gesture != leaderGesture)
                                    + " replacedOlderPending=" + delivery.replacedOlderPending
                                    + " engine=" + (bitmapDocument == null
                                    ? "none" : bitmapDocument.engine())
                                    + " chars=" + (screen == null ? 0 : screen.chars().size())
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    if (usable(screen)) {
                        resolveAgainstFullDocument(state, frame, delivery.gesture,
                                screen, delivery.callback);
                    } else {
                        resolveWithoutFullDocument(state, frame, delivery.gesture,
                                delivery.callback,
                                new IllegalStateException("full-screen OCR has no selectable text"));
                    }
                } catch (Throwable t) {
                    PendingResolve delivery = finishFullScreenFlight(
                            state, leaderGesture, leaderCallback);
                    resolveWithoutFullDocument(state, frame, delivery.gesture,
                            delivery.callback, t);
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                PendingResolve delivery = finishFullScreenFlight(
                        state, leaderGesture, leaderCallback);
                DiagnosticLog.i(state.app, "G_CIRCLE_FULL_OCR", "failed gesture="
                        + delivery.gesture.kind + " engine="
                        + CircleStableOcr.fullModeLabel(state.app)
                        + " error=" + safe(error));
                resolveWithoutFullDocument(state, frame, delivery.gesture,
                        delivery.callback, error);
            }
        });
    }

    private static void resolveAgainstFullDocument(PreloadState state,
                                                    GoogleCircleCapture.Frame frame,
                                                    GoogleCircleSelection.Selection gesture,
                                                    OcrDocument fullDocument,
                                                    Callback callback) {
        CircleSelectionPlanner.Plan plan = CircleSelectionPlanner.plan(
                state.app, frame, gesture, fullDocument);
        OcrDocument fullSelection = plan == null ? null : plan.baselineSelection;
        if (!usable(fullSelection)) {
            // Keep one compatibility fallback for malformed legacy OCR documents. Normal full-screen
            // OCR should always be handled by CircleSelectionPlanner.
            fullSelection = initialSelection(state.app, frame, gesture, fullDocument);
        }

        int correctionMode = new FloatSettings(state.app).circleCorrectionEngine();
        if (correctionMode == 0) {
            if (usable(fullSelection)) {
                DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_COMPARE", "gesture=" + gesture.kind
                        + " correction=off winner=full"
                        + " plan=" + planMode(plan)
                        + " selectedChars=" + fullSelection.chars().size());
                callback.onResolved(new Result(Source.IMAGE_OCR, fullDocument, fullSelection,
                        selectionScreenBounds(plan, frame, gesture), fullBitmapRoi(frame),
                        null, false));
            } else {
                callback.onResolved(failure(frame, gesture, fullBitmapRoi(frame),
                        new IllegalStateException("full-screen OCR selection miss"), false));
            }
            return;
        }
        runCorrection(state, frame, gesture, fullDocument, fullSelection, plan, callback);
    }

    private static void resolveWithoutFullDocument(PreloadState state,
                                                    GoogleCircleCapture.Frame frame,
                                                    GoogleCircleSelection.Selection gesture,
                                                    Callback callback,
                                                    Throwable fullError) {
        if (new FloatSettings(state.app).circleCorrectionEngine() == 0) {
            callback.onResolved(failure(frame, gesture, fullBitmapRoi(frame), fullError, false));
            return;
        }
        // No baseline document means no SelectionPlan can be created. This remains a degraded
        // compatibility path only; the normal flow always plans from the cached full-screen OCR.
        runCorrection(state, frame, gesture, null, null, null, callback);
    }

    private static void runCorrection(PreloadState state,
                                      GoogleCircleCapture.Frame frame,
                                      GoogleCircleSelection.Selection gesture,
                                      OcrDocument fullDocument,
                                      OcrDocument fullSelection,
                                      CircleSelectionPlanner.Plan plan,
                                      Callback callback) {
        if (!isCurrent(state, frame)) return;
        Rect roi = plan != null && !plan.correctionBitmapRoi.isEmpty()
                ? new Rect(plan.correctionBitmapRoi)
                : localBitmapRoi(state.app, frame, gesture);
        if (roi.isEmpty()) {
            deliverFullOrFailure(frame, gesture, fullDocument, fullSelection, plan, roi, callback,
                    new IllegalStateException("empty correction ROI"));
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(frame.bitmap, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            deliverFullOrFailure(frame, gesture, fullDocument, fullSelection, plan, roi, callback, t);
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_CORRECTION", "start gesture=" + gesture.kind
                + " roi=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " engine=" + CircleStableOcr.correctionModeLabel(state.app)
                + " plan=" + planMode(plan)
                + " planRows=" + (plan == null ? 0 : plan.rowCount())
                + " fullSelectionChars=" + (fullSelection == null ? 0 : fullSelection.chars().size()));

        CircleStableOcr.recognizeCorrectionSelected(state.app, crop,
                new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument localBitmap) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument parent = localBitmap == null ? null
                            : localBitmap.translated(roi.left, roi.top,
                                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
                    OcrDocument localScreen = parent == null ? null
                            : frame.transform.documentBitmapToScreen(parent);
                    OcrDocument correctionSelection = plan == null
                            ? initialSelection(state.app, frame, gesture, localScreen)
                            : CircleSelectionPlanner.selectCorrection(
                                    state.app, frame, plan, localScreen);

                    if (!usable(correctionSelection)) {
                        DiagnosticLog.i(state.app, "G_CIRCLE_CORRECTION",
                                "planned correction rejected -> keep full gesture=" + gesture.kind
                                        + " plan=" + planMode(plan));
                        deliverFullOrFailure(frame, gesture, fullDocument, fullSelection, plan, roi,
                                callback, new IllegalStateException("correction plan coverage miss"));
                        return;
                    }

                    String fullText = selectionText(fullSelection);
                    String correctionText = selectionText(correctionSelection);
                    boolean same = usable(fullSelection)
                            && compact(fullText).equals(compact(correctionText));
                    String winner = same ? "full" : "correction";
                    DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_COMPARE",
                            "gesture=" + gesture.kind
                                    + " plan=" + planMode(plan)
                                    + " same=" + same
                                    + " winner=" + winner
                                    + " selectionOwner=CircleSelectionPlanner"
                                    + " fullEngine=" + (fullDocument == null
                                    ? "none" : fullDocument.engine())
                                    + " correctionEngine=" + (localBitmap == null
                                    ? "none" : localBitmap.engine())
                                    + " fullChars=" + (fullSelection == null
                                    ? 0 : fullSelection.chars().size())
                                    + " correctionChars=" + correctionSelection.chars().size()
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));

                    if (same || !usable(fullSelection)) {
                        if (same) {
                            callback.onResolved(new Result(Source.IMAGE_OCR,
                                    fullDocument, fullSelection,
                                    selectionScreenBounds(plan, frame, gesture), fullBitmapRoi(frame),
                                    null, false));
                        } else {
                            callback.onResolved(new Result(Source.IMAGE_OCR,
                                    localScreen, correctionSelection,
                                    selectionScreenBounds(plan, frame, gesture), roi,
                                    null, true));
                        }
                    } else {
                        // PP may change recognized text, but correctionSelection was produced from the
                        // existing SelectionPlan, so it cannot shrink a multi-line gesture back to a
                        // diagonal strip or select a different set of rows.
                        callback.onResolved(new Result(Source.IMAGE_OCR,
                                localScreen, correctionSelection,
                                selectionScreenBounds(plan, frame, gesture), roi,
                                null, true));
                    }
                } catch (Throwable t) {
                    deliverFullOrFailure(frame, gesture, fullDocument, fullSelection, plan, roi,
                            callback, t);
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "G_CIRCLE_CORRECTION", "failed gesture="
                        + gesture.kind + " engine="
                        + CircleStableOcr.correctionModeLabel(state.app)
                        + " plan=" + planMode(plan)
                        + " error=" + safe(error)
                        + " fallback=" + (usable(fullSelection) ? "full" : "none"));
                deliverFullOrFailure(frame, gesture, fullDocument, fullSelection, plan, roi,
                        callback, error);
            }
        });
    }

    private static void deliverFullOrFailure(GoogleCircleCapture.Frame frame,
                                             GoogleCircleSelection.Selection gesture,
                                             OcrDocument fullDocument,
                                             OcrDocument fullSelection,
                                             CircleSelectionPlanner.Plan plan,
                                             Rect roi,
                                             Callback callback,
                                             Throwable error) {
        if (usable(fullDocument) && usable(fullSelection)) {
            callback.onResolved(new Result(Source.IMAGE_OCR, fullDocument, fullSelection,
                    selectionScreenBounds(plan, frame, gesture), fullBitmapRoi(frame), null, false));
        } else {
            callback.onResolved(failure(frame, gesture, roi, error, true));
        }
    }

    private static PendingResolve finishFullScreenFlight(PreloadState state,
                                                          GoogleCircleSelection.Selection originalGesture,
                                                          Callback originalCallback) {
        synchronized (STATE_LOCK) {
            if (current != state) {
                return new PendingResolve(originalGesture, originalCallback, false);
            }
            state.fullScreenOcrInFlight = false;
            if (state.pendingGesture != null && state.pendingCallback != null) {
                GoogleCircleSelection.Selection latestGesture = state.pendingGesture;
                Callback latestCallback = state.pendingCallback;
                boolean replaced = state.pendingReplaced;
                state.pendingGesture = null;
                state.pendingCallback = null;
                state.pendingReplaced = false;
                return new PendingResolve(latestGesture, latestCallback, replaced);
            }
            state.pendingGesture = null;
            state.pendingCallback = null;
            state.pendingReplaced = false;
            return new PendingResolve(originalGesture, originalCallback, false);
        }
    }

    private static OcrDocument initialSelection(Context app, GoogleCircleCapture.Frame frame,
                                                GoogleCircleSelection.Selection gesture,
                                                OcrDocument document) {
        if (!usable(document)) return null;
        return CircleGestureTextSelector.selectDocument(app, frame, gesture, document,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                "circle-gesture-selected");
    }

    /** Degraded fallback used only when a full-screen baseline document could not be planned. */
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

    private static Rect fullBitmapRoi(GoogleCircleCapture.Frame frame) {
        return new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
    }

    private static Rect selectionScreenBounds(CircleSelectionPlanner.Plan plan,
                                              GoogleCircleCapture.Frame frame,
                                              GoogleCircleSelection.Selection gesture) {
        if (plan != null && !plan.selectionBoundsScreen.isEmpty()) {
            return new Rect(plan.selectionBoundsScreen);
        }
        return gestureScreenBounds(frame, gesture);
    }

    private static String planMode(CircleSelectionPlanner.Plan plan) {
        return plan == null ? "LEGACY_FALLBACK" : plan.mode.name();
    }

    private static String selectionText(OcrDocument document) {
        return document == null ? "" : document.fullText();
    }

    private static String compact(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            out.appendCodePoint(Character.toLowerCase(cp));
        }
        return out.toString();
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
        state.fullScreenDocument = null;
        state.fullScreenOcrInFlight = false;
        state.pendingGesture = null;
        state.pendingCallback = null;
        state.pendingReplaced = false;
    }

    private static Rect gestureScreenBounds(GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        Rect bitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
    }

    private static Result failure(GoogleCircleCapture.Frame frame,
                                  GoogleCircleSelection.Selection gesture,
                                  Rect roi, Throwable error, boolean local) {
        return new Result(Source.NONE, null, null,
                gestureScreenBounds(frame, gesture), roi, error, local);
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
