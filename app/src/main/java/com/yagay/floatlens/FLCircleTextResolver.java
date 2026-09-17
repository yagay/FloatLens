package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.lang.ref.WeakReference;

/**
 * Frozen-screen OCR resolver for Circle.
 *
 * <p>The selected full-screen OCR engine produces one cached SCREEN-space document. Selection
 * semantics and correction ROI are owned only by {@link CircleSelectionPlanner}. Optional PP-OCR
 * correction may refine text inside that plan, but it can never reinterpret the gesture or create
 * its own fallback ROI.</p>
 */
final class FLCircleTextResolver {
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
        final FLCircleSelection.Selection gesture;
        final Callback callback;
        final boolean replacedOlderPending;

        PendingResolve(FLCircleSelection.Selection gesture, Callback callback,
                       boolean replacedOlderPending) {
            this.gesture = gesture;
            this.callback = callback;
            this.replacedOlderPending = replacedOlderPending;
        }
    }

    private static final class PreloadState {
        final long generation;
        final Context app;
        final WeakReference<FLCircleCapture.Frame> frameRef;
        OcrDocument fullScreenDocument;
        boolean fullScreenOcrInFlight;
        FLCircleSelection.Selection pendingGesture;
        Callback pendingCallback;
        boolean pendingReplaced;

        PreloadState(long generation, Context app, FLCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }
    }

    private static final Object STATE_LOCK = new Object();
    private static long generation;
    private static PreloadState current;

    static void preload(Context context, FLCircleCapture.Frame frame) {
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
        DiagnosticLog.i(app, "FL_CIRCLE_OCR", "ready generation=" + created.generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " fullEngine=" + CircleStableOcr.fullModeLabel(app)
                + " correctionEngine=" + CircleStableOcr.correctionModeLabel(app)
                + " fullMode=" + fs.circleFullOcrEngine()
                + " correctionMode=" + fs.circleCorrectionEngine()
                + " strategy=full_screen_once_plus_planned_correction"
                + " selectionOwner=CircleSelectionPlanner"
                + " roiOwner=CircleSelectionPlanner"
                + " correctionPolicy=preserve_selection_plan"
                + " pendingGesturePolicy=latest_wins"
                + " viewText=false coordinateSpace=SCREEN");
    }

    static void resolve(Context context, FLCircleCapture.Frame frame,
                        FLCircleSelection.Selection gesture, Callback callback) {
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
            DiagnosticLog.i(app, "FL_CIRCLE_FULL_OCR", "coalesced gesture=" + gesture.kind
                    + " policy=latest_wins replacedPending=" + replaced
                    + " newBitmapCopy=false newOcrRequest=false");
            return;
        }
        startFullScreenOcr(state, frame, gesture, callback);
    }

    static void release(Context context, FLCircleCapture.Frame frame, String reason) {
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
        DiagnosticLog.i(app == null ? released.app : app, "FL_CIRCLE_OCR",
                "release generation=" + released.generation
                        + " reason=" + (reason == null ? "unknown" : reason));
    }

    private static void startFullScreenOcr(PreloadState state,
                                           FLCircleCapture.Frame frame,
                                           FLCircleSelection.Selection leaderGesture,
                                           Callback leaderCallback) {
        Rect fullRoi = fullBitmapRoi(frame);
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
        DiagnosticLog.i(state.app, "FL_CIRCLE_FULL_OCR", "start gesture=" + leaderGesture.kind
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
                    DiagnosticLog.i(state.app, "FL_CIRCLE_FULL_OCR",
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
                        delivery.callback.onResolved(failure(frame, delivery.gesture, fullRoi,
                                new IllegalStateException("full-screen OCR has no selectable text"),
                                false));
                    }
                } catch (Throwable t) {
                    PendingResolve delivery = finishFullScreenFlight(
                            state, leaderGesture, leaderCallback);
                    delivery.callback.onResolved(failure(frame, delivery.gesture, fullRoi, t, false));
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                PendingResolve delivery = finishFullScreenFlight(
                        state, leaderGesture, leaderCallback);
                DiagnosticLog.i(state.app, "FL_CIRCLE_FULL_OCR", "failed gesture="
                        + delivery.gesture.kind + " engine="
                        + CircleStableOcr.fullModeLabel(state.app)
                        + " error=" + safe(error)
                        + " correctionSkipped=no_selection_plan");
                delivery.callback.onResolved(failure(frame, delivery.gesture, fullRoi, error, false));
            }
        });
    }

    private static void resolveAgainstFullDocument(PreloadState state,
                                                    FLCircleCapture.Frame frame,
                                                    FLCircleSelection.Selection gesture,
                                                    OcrDocument fullDocument,
                                                    Callback callback) {
        CircleSelectionPlanner.Plan plan = CircleSelectionPlanner.plan(
                state.app, frame, gesture, fullDocument);
        if (plan == null || !usable(plan.baselineSelection)) {
            DiagnosticLog.i(state.app, "FL_CIRCLE_SELECTION_PLAN",
                    "kind=" + gesture.kind + " accepted=false fallback=none");
            callback.onResolved(failure(frame, gesture, fullBitmapRoi(frame),
                    new IllegalStateException("Circle selection plan miss"), false));
            return;
        }

        OcrDocument fullSelection = plan.baselineSelection;
        if (new FloatSettings(state.app).circleCorrectionEngine() == 0) {
            DiagnosticLog.i(state.app, "FL_CIRCLE_TEXT_COMPARE", "gesture=" + gesture.kind
                    + " correction=off winner=full"
                    + " plan=" + plan.mode
                    + " selectedChars=" + fullSelection.chars().size());
            callback.onResolved(new Result(Source.IMAGE_OCR, fullDocument, fullSelection,
                    plan.selectionBoundsScreen, fullBitmapRoi(frame), null, false));
            return;
        }
        runCorrection(state, frame, fullDocument, plan, callback);
    }

    private static void runCorrection(PreloadState state,
                                      FLCircleCapture.Frame frame,
                                      OcrDocument fullDocument,
                                      CircleSelectionPlanner.Plan plan,
                                      Callback callback) {
        if (!isCurrent(state, frame)) return;
        OcrDocument fullSelection = plan.baselineSelection;
        Rect roi = new Rect(plan.correctionBitmapRoi);
        if (roi.isEmpty()) {
            deliverFull(frame, fullDocument, plan, callback,
                    new IllegalStateException("empty planned correction ROI"));
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(frame.bitmap, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            deliverFull(frame, fullDocument, plan, callback, t);
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "FL_CIRCLE_CORRECTION", "start gesture=" + plan.gesture.kind
                + " roi=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " engine=" + CircleStableOcr.correctionModeLabel(state.app)
                + " plan=" + plan.mode
                + " planRows=" + plan.rowCount()
                + " fullSelectionChars=" + fullSelection.chars().size()
                + " roiOwner=CircleSelectionPlanner");

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
                    OcrDocument correctionSelection = CircleSelectionPlanner.selectCorrection(
                            state.app, frame, plan, localScreen);

                    if (!usable(correctionSelection)) {
                        DiagnosticLog.i(state.app, "FL_CIRCLE_CORRECTION",
                                "planned correction rejected -> keep full gesture=" + plan.gesture.kind
                                        + " plan=" + plan.mode);
                        deliverFull(frame, fullDocument, plan, callback,
                                new IllegalStateException("correction plan coverage miss"));
                        return;
                    }

                    String fullText = selectionText(fullSelection);
                    String correctionText = selectionText(correctionSelection);
                    boolean same = compact(fullText).equals(compact(correctionText));
                    DiagnosticLog.i(state.app, "FL_CIRCLE_TEXT_COMPARE",
                            "gesture=" + plan.gesture.kind
                                    + " plan=" + plan.mode
                                    + " same=" + same
                                    + " winner=" + (same ? "full" : "correction")
                                    + " selectionOwner=CircleSelectionPlanner"
                                    + " roiOwner=CircleSelectionPlanner"
                                    + " fullEngine=" + fullDocument.engine()
                                    + " correctionEngine=" + (localBitmap == null
                                    ? "none" : localBitmap.engine())
                                    + " fullChars=" + fullSelection.chars().size()
                                    + " correctionChars=" + correctionSelection.chars().size()
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));

                    if (same) {
                        callback.onResolved(new Result(Source.IMAGE_OCR,
                                fullDocument, fullSelection, plan.selectionBoundsScreen,
                                fullBitmapRoi(frame), null, false));
                    } else {
                        callback.onResolved(new Result(Source.IMAGE_OCR,
                                localScreen, correctionSelection, plan.selectionBoundsScreen,
                                roi, null, true));
                    }
                } catch (Throwable t) {
                    deliverFull(frame, fullDocument, plan, callback, t);
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "FL_CIRCLE_CORRECTION", "failed gesture="
                        + plan.gesture.kind + " engine="
                        + CircleStableOcr.correctionModeLabel(state.app)
                        + " plan=" + plan.mode
                        + " error=" + safe(error)
                        + " fallback=planned_full");
                deliverFull(frame, fullDocument, plan, callback, error);
            }
        });
    }

    private static void deliverFull(FLCircleCapture.Frame frame,
                                    OcrDocument fullDocument,
                                    CircleSelectionPlanner.Plan plan,
                                    Callback callback,
                                    Throwable correctionError) {
        if (usable(fullDocument) && plan != null && usable(plan.baselineSelection)) {
            callback.onResolved(new Result(Source.IMAGE_OCR, fullDocument,
                    plan.baselineSelection, plan.selectionBoundsScreen,
                    fullBitmapRoi(frame), correctionError, false));
            return;
        }
        FLCircleSelection.Selection gesture = plan == null ? null : plan.gesture;
        if (gesture != null) {
            callback.onResolved(failure(frame, gesture,
                    plan.correctionBitmapRoi, correctionError, false));
        }
    }

    private static PendingResolve finishFullScreenFlight(PreloadState state,
                                                          FLCircleSelection.Selection originalGesture,
                                                          Callback originalCallback) {
        synchronized (STATE_LOCK) {
            if (current != state) {
                return new PendingResolve(originalGesture, originalCallback, false);
            }
            state.fullScreenOcrInFlight = false;
            if (state.pendingGesture != null && state.pendingCallback != null) {
                FLCircleSelection.Selection latestGesture = state.pendingGesture;
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

    private static Rect fullBitmapRoi(FLCircleCapture.Frame frame) {
        return new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
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

    private static boolean sameFrame(PreloadState state, FLCircleCapture.Frame frame) {
        return state != null && state.frameRef.get() == frame;
    }

    private static boolean isCurrent(PreloadState state, FLCircleCapture.Frame frame) {
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

    private static Rect gestureScreenBounds(FLCircleCapture.Frame frame,
                                            FLCircleSelection.Selection gesture) {
        Rect bitmap = FLCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
    }

    private static Result failure(FLCircleCapture.Frame frame,
                                  FLCircleSelection.Selection gesture,
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

    private FLCircleTextResolver() {}
}
