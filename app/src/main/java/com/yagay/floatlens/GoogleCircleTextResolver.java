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
 * Frozen-screen text resolver for the Google-style Circle workspace.
 *
 * <p>View text and independent OCR passes are prepared once when Circle opens. Full-frame and tile
 * OCR documents are never globally merged. At gesture time only candidates touched by the real
 * tap/highlight/scribble path are compared, and multi-pass consensus chooses the local result.
 * Tight local multi-scale OCR is reserved for a genuine cache miss.</p>
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

        OcrDocument viewDocument;
        CircleOcrIndex ocrIndex;
        Throwable ocrError;
        boolean viewDone;
        boolean ocrDone;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }

        boolean ready() { return viewDone && ocrDone; }
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
    private static final float VIEW_TAP_TOLERANCE_DP = 0f;
    private static final float CACHED_OCR_TAP_TOLERANCE_DP = 0f;
    private static final float LOCAL_OCR_TAP_TOLERANCE_DP = 28f;
    private static final float RANGE_CORRIDOR_DP = 6f;

    private static final float LOCAL_TAP_HALF_SIZE_DP = 38f;
    // The crop must contain the full +/-18dp OCR stroke corridor. Pixels outside that corridor are
    // neutralized before recognition, so this padding does not reintroduce rectangular interference.
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
                + " strategy=frozen_view_then_independent_ocr_passes"
                + " routing=gesture_local_consensus"
                + " viewMask=exact_character_geometry_only"
                + " fallback=local_multiscale_pixel_ocr"
                + " geometry=shared_matrix_transform"
                + " coordinateSpace=SCREEN");

        // View text must finish first because only its reliable, native per-character geometry is
        // used as a negative OCR mask. OCR never consumes View text content or semantic labels.
        VIEW_IO.execute(() -> prepareViewPart(state, frame, started));
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
                        new IllegalStateException("text index unavailable"), false));
                return;
            }
            ready = state.ready();
            if (!ready) state.pending.add(new PendingResolve(gesture, callback));
        }

        if (!ready) {
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "wait for preindex gesture="
                    + gesture.kind + " generation=" + state.generation);
            return;
        }
        resolvePrepared(state, frame, gesture, callback);
    }

    /** Release all workspace-scoped documents and pending callbacks as soon as the overlay closes. */
    static void release(Context c, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = c == null ? null : c.getApplicationContext();
        PreloadState released;
        int viewChars;
        int ocrPasses;
        int ocrChars;
        int pending;
        synchronized (INDEX_LOCK) {
            if (!sameFrame(currentIndex, frame)) return;
            released = currentIndex;
            viewChars = released.viewDocument == null ? 0 : released.viewDocument.chars().size();
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
                + " viewChars=" + viewChars
                + " ocrPasses=" + ocrPasses
                + " ocrChars=" + ocrChars
                + " pendingCleared=" + pending);
    }

    private static void clearStateLocked(PreloadState state) {
        if (state == null) return;
        state.pending.clear();
        state.viewDocument = null;
        state.ocrIndex = null;
        state.ocrError = null;
        state.viewDone = false;
        state.ocrDone = false;
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
        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return;
            state.viewDocument = view;
            state.viewDone = true;
        }
        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_INDEX", "view ready generation="
                + state.generation
                + " rawChars=" + view.chars().size()
                + " exactChars=" + countExactChars(view)
                + " ownership=screen_selection_model"
                + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                + (error == null ? "" : " error=" + ScreenCaptureBackend.safeMessage(error)));

        if (!isCurrent(state, frame)) return;
        INDEX_IO.execute(() -> prepareOcrPart(state, frame, started));
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
        final int maskedViewChars;
        try {
            Bitmap made = source.copy(Bitmap.Config.ARGB_8888, true);
            if (made == null) throw new IllegalStateException("OCR frame copy failed");
            copy = made;
            maskedViewChars = ViewTextOcrMask.apply(state.app, copy,
                    new Rect(0, 0, source.getWidth(), source.getHeight()),
                    state.viewDocument, frame.transform);
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
                + " roi=full_plus_overlap_tiles"
                + " requestsPerWorkspace=1_full_plus_4_tiles"
                + " merge=none"
                + " exactViewCharsMasked=" + maskedViewChars
                + " semanticLabels=false"
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
                + " viewChars=" + (state.viewDocument == null ? 0 : state.viewDocument.chars().size())
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
        OcrDocument selectedView = selectViewDocument(frame, gesture, state.viewDocument);

        if (usable(selectedView)) {
            OcrDocument chosen = selectedView;
            Source source = Source.VIEW;
            int refined = 0;
            int approximate = 0;

            OcrDocument fullOcr = state.ocrIndex == null ? null : state.ocrIndex.fullFrameDocument();
            if (usable(fullOcr)) {
                try {
                    ViewTextGeometryRefiner.Result geometry =
                            ViewTextGeometryRefiner.refine(selectedView, fullOcr);
                    if (geometry != null && geometry.document() != null) {
                        chosen = geometry.document();
                        refined = geometry.refinedChars();
                        approximate = geometry.approximateChars();
                        if (refined > 0) source = Source.VIEW_OCR;
                    }
                } catch (Throwable t) {
                    DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE",
                            "view geometry refine failed=" + ScreenCaptureBackend.safeMessage(t));
                }
            }

            OcrDocument scopedView = CircleGestureTextSelector.selectDocument(
                    state.app, frame, gesture, chosen,
                    VIEW_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP, "view-gesture-selected");
            if (usable(scopedView)) {
                DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "indexed hit gesture="
                        + gesture.kind + " source=" + source
                        + " chars=" + scopedView.chars().size()
                        + " refinedChars=" + refined
                        + " approximateChars=" + approximate
                        + " textOwner=ACCESSIBILITY_VIEW"
                        + " pathHit=true preIndex=true coordinateSpace=SCREEN");
                callback.onResolved(new Result(source, scopedView,
                        gestureScreen, new Rect(), null, false));
                return;
            }

            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE",
                    "view candidate rejected gesture=" + gesture.kind
                            + " chars=" + chosen.chars().size()
                            + " refinedChars=" + refined
                            + " pathHit=false");
        }

        CircleOcrConsensus.Result consensus = CircleOcrConsensus.resolve(
                state.app, frame, gesture, state.ocrIndex,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP);
        if (consensus != null && usable(consensus.document)) {
            Rect fullRoi = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "indexed hit gesture="
                    + gesture.kind + " source=IMAGE_OCR"
                    + " chars=" + consensus.document.chars().size()
                    + " passHits=" + consensus.passHits
                    + " clusters=" + consensus.clusters
                    + " maxSupport=" + consensus.maxSupport
                    + " consensus=true localFallback=false"
                    + " preIndex=true coordinateSpace=SCREEN");
            callback.onResolved(new Result(Source.IMAGE_OCR, consensus.document,
                    gestureScreen, fullRoi, null, false));
            return;
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "cached miss gesture="
                + gesture.kind
                + " cachedOcrAvailable=" + (state.ocrIndex != null && !state.ocrIndex.isEmpty())
                + " -> local enhanced pixel OCR");
        resolveLocalEnhancedOcr(state, frame, gesture, gestureScreen, callback);
    }

    private static void resolveLocalEnhancedOcr(PreloadState state,
                                                GoogleCircleCapture.Frame frame,
                                                GoogleCircleSelection.Selection gesture,
                                                Rect gestureScreen,
                                                Callback callback) {
        if (!isCurrent(state, frame)) return;
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, new Rect(),
                    new IllegalStateException("frozen screenshot unavailable"), true));
            return;
        }

        Rect roi = localRecognitionRoi(state.app, frame, gesture);
        if (roi.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi,
                    state.ocrError, true));
            return;
        }

        final Bitmap crop;
        final int maskedViewChars;
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
            maskedViewChars = ViewTextOcrMask.apply(state.app, crop, roi,
                    state.viewDocument, frame.transform);
            corridor = GestureOcrCorridorMask.apply(state.app, crop, roi,
                    gesture, frame.transform);
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, t, true));
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
                + " enhancement=internal_multiscale"
                + " exactViewCharsMasked=" + maskedViewChars
                + " corridorApplied=" + corridor.applied
                + " corridorPoints=" + corridor.pointCount
                + " corridorHalfWidthBitmapPx=" + Math.round(corridor.halfWidthBitmapPx)
                + " geometry=roi_to_screen_matrix"
                + " enginePolicy=follow_main_setting"
                + " pixelOnly=true semanticLabels=false nearbyCaptionSearch=false");

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
                OcrDocument scoped = CircleGestureTextSelector.selectDocument(
                        state.app, frame, gesture, screen,
                        LOCAL_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                        "local-gesture-selected");
                boolean hit = usable(scoped);
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "done gesture=" + gesture.kind
                        + " hit=" + hit
                        + " chars=" + (scoped == null ? 0 : scoped.chars().size())
                        + " text=" + summarize(scoped == null ? "" : scoped.fullText())
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                        + " coordinateSpace=SCREEN pixelOnly=true");
                callback.onResolved(new Result(hit ? Source.IMAGE_OCR : Source.NONE,
                        hit ? scoped : null, gestureScreen, roi, null, true));
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "failed gesture="
                        + gesture.kind + " error=" + ScreenCaptureBackend.safeMessage(error));
                callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, error, true));
            }
        });
    }

    private static Rect localRecognitionRoi(Context app, GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        if (frame == null || gesture == null || frame.bitmap == null) return new Rect();

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

    private static OcrDocument selectViewDocument(GoogleCircleCapture.Frame frame,
                                                  GoogleCircleSelection.Selection gesture,
                                                  OcrDocument viewDocument) {
        if (frame == null || gesture == null || !usable(viewDocument)) {
            return emptyScreenDocument(viewDocument, "view-selected-empty");
        }

        ArrayList<ScreenCandidate> candidates = new ArrayList<>();
        for (OcrDocument.Line line : viewDocument.lines()) {
            if (line == null || line.text().isBlank() || line.bounds().isEmpty()) continue;
            candidates.add(new ScreenCandidate(line.bounds(), ScreenCandidate.Type.TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY, line.text(),
                    "", "", "", "", 0,
                    false, false, false, false, false));
        }
        if (candidates.isEmpty()) return emptyScreenDocument(viewDocument, "view-selected-empty");

        ScreenSelectionModel model = new ScreenSelectionModel();
        model.setAccessibility(candidates);
        ArrayList<ScreenCandidate> selected = new ArrayList<>();

        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            PointF p = bitmapPointToScreen(frame, gesture.focus);
            ScreenCandidate hit = model.selectAccessibilityAt(p.x, p.y);
            if (hit != null) selected.add(hit);
        } else {
            Rect region = gestureScreenBounds(frame, gesture);
            if (!region.isEmpty()) {
                for (ScreenCandidate candidate : model.accessibilityCandidates()) {
                    Rect bounds = candidate.bounds();
                    if (bounds.isEmpty() || !Rect.intersects(region, bounds)) continue;
                    boolean broadWrapper = false;
                    for (ScreenCandidate kept : selected) {
                        if (contains(bounds, kept.bounds())) {
                            broadWrapper = true;
                            break;
                        }
                    }
                    if (!broadWrapper) selected.add(candidate);
                }
            }
        }

        if (selected.isEmpty()) return emptyScreenDocument(viewDocument, "view-selected-empty");
        return subsetDocument(viewDocument, selected);
    }

    private static OcrDocument subsetDocument(OcrDocument source, List<ScreenCandidate> selected) {
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int order = 0;

        for (ScreenCandidate candidate : selected) {
            OcrDocument.Line match = null;
            for (OcrDocument.Line line : source.lines()) {
                if (line == null || line.bounds().isEmpty()) continue;
                if (!line.bounds().equals(candidate.bounds())) continue;
                if (!MlKitTextCore.compact(line.text()).equals(
                        MlKitTextCore.compact(candidate.text()))) continue;
                match = line;
                break;
            }
            if (match == null) continue;

            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect lineBounds = null;
            for (OcrDocument.CharUnit c : match.chars()) {
                if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
                Rect bounds = c.bounds();
                chars.add(new OcrDocument.CharUnit(c.text(), bounds, c.confidence(),
                        lineId, c.group(), order++));
                if (lineBounds == null) lineBounds = new Rect(bounds); else lineBounds.union(bounds);
            }
            if (chars.isEmpty()) continue;
            if (lineBounds == null || lineBounds.isEmpty()) lineBounds = match.bounds();
            String text = match.text();
            lines.add(new OcrDocument.Line(text, lineBounds, match.confidence(), chars));
            blocks.add(text);
            if (full.length() > 0) full.append('\n');
            full.append(text);
            lineId++;
        }

        if (lines.isEmpty()) return emptyScreenDocument(source, "view-selected-empty");
        return OcrDocument.screenSpace(full.toString(), blocks, lines,
                "view-selected", source.confidence(), source.score(),
                source.imageWidth(), source.imageHeight());
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isScreenSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static int countExactChars(OcrDocument document) {
        if (document == null) return 0;
        int count = 0;
        for (OcrDocument.CharUnit c : document.chars()) {
            if (c != null && c.confidence() >= 0.999f) count++;
        }
        return count;
    }

    private static boolean contains(Rect outer, Rect inner) {
        return outer != null && inner != null && !outer.isEmpty() && !inner.isEmpty()
                && outer.left <= inner.left && outer.top <= inner.top
                && outer.right >= inner.right && outer.bottom >= inner.bottom;
    }

    private static boolean sameFrame(PreloadState state, GoogleCircleCapture.Frame frame) {
        return state != null && state.frameRef.get() == frame;
    }

    private static boolean isCurrent(PreloadState state, GoogleCircleCapture.Frame frame) {
        synchronized (INDEX_LOCK) {
            return currentIndex == state && sameFrame(state, frame);
        }
    }

    private static OcrDocument emptyScreenDocument(OcrDocument source, String engine) {
        int width = source == null ? 1 : source.imageWidth();
        int height = source == null ? 1 : source.imageHeight();
        return OcrDocument.screenSpace("", List.of(), List.of(), engine, 1f, 0d,
                Math.max(1, width), Math.max(1, height));
    }

    private static Rect gestureScreenBounds(GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        Rect bitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
    }

    private static PointF bitmapPointToScreen(GoogleCircleCapture.Frame frame, PointF bitmapPoint) {
        return frame.bitmapPointToScreen(bitmapPoint.x, bitmapPoint.y);
    }

    private static float dp(Context app, float value) {
        return value * ScreenGeometry.density(app);
    }

    private static String summarize(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 48 ? oneLine : oneLine.substring(0, 48) + "…";
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleTextResolver() {}
}
