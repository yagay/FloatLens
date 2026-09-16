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
 * Frozen-screen text index for the Google-style Circle workspace.
 *
 * <p>View text and full-frame OCR are prepared once when Circle opens. All OCR geometry is
 * normalized to absolute SCREEN coordinates through the capture frame's shared Matrix transform.
 * If the cached OCR misses the gesture, a tight pixel-only ROI is cropped once at native screenshot
 * resolution and the multi-scale OCR pipeline handles all enhancement internally. No package name,
 * Accessibility semantic label, contentDescription or nearby app caption is used as recognition
 * input.</p>
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
        OcrDocument ocrDocument;
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
    private static final float VIEW_TAP_TOLERANCE_DP = 10f;
    private static final float CACHED_OCR_TAP_TOLERANCE_DP = 10f;
    private static final float LOCAL_OCR_TAP_TOLERANCE_DP = 28f;
    private static final float RANGE_TOLERANCE_DP = 6f;

    // Keep the local TAP crop tightly centered on image pixels instead of searching nearby captions.
    private static final float LOCAL_TAP_HALF_SIZE_DP = 38f; // 76dp square in SCREEN space
    private static final float LOCAL_RANGE_PAD_DP = 4f;

    private static long indexGeneration;
    private static PreloadState currentIndex;

    /** Start one frozen-screen View+OCR index as soon as capture completes. */
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
                + " fallback=cached_hit_else_local_pixel_ocr"
                + " geometry=shared_matrix_transform"
                + " localPixelOnly=true coordinateSpace=SCREEN");

        VIEW_IO.execute(() -> prepareViewPart(state, frame, started));
        INDEX_IO.execute(() -> prepareOcrPart(state, frame, started));
    }

    /** Resolve from the prebuilt index; local enhanced OCR is only a miss fallback. */
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
                        new IllegalStateException("text index unavailable")));
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
                OcrDocument screen = frame.transform.documentBitmapToScreen(document);
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
                + " viewChars=" + (state.viewDocument == null ? 0 : state.viewDocument.chars().size())
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
        OcrDocument selectedView = selectViewDocument(frame, gesture, state.viewDocument);

        if (usable(selectedView)) {
            OcrDocument chosen = selectedView;
            Source source = Source.VIEW;
            int refined = 0;
            int approximate = 0;

            OcrDocument ocr = state.ocrDocument;
            if (usable(ocr)) {
                try {
                    ViewTextGeometryRefiner.Result geometry =
                            ViewTextGeometryRefiner.refine(selectedView, ocr);
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

            boolean charHit = documentHitsGesture(state.app, frame, gesture, chosen,
                    VIEW_TAP_TOLERANCE_DP, RANGE_TOLERANCE_DP);
            if (charHit) {
                DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "indexed hit gesture="
                        + gesture.kind + " source=" + source
                        + " chars=" + chosen.chars().size()
                        + " textChars=" + chosen.fullText().length()
                        + " refinedChars=" + refined
                        + " approximateChars=" + approximate
                        + " textOwner=ACCESSIBILITY_VIEW"
                        + " charHit=true preIndex=true coordinateSpace=SCREEN");
                callback.onResolved(new Result(source, chosen, gestureScreen, new Rect(), null));
                return;
            }

            DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE",
                    "view candidate rejected gesture=" + gesture.kind
                            + " chars=" + chosen.chars().size()
                            + " refinedChars=" + refined
                            + " charHit=false");
        }

        OcrDocument ocr = state.ocrDocument;
        if (usable(ocr)) {
            boolean cachedHit = documentHitsGesture(state.app, frame, gesture, ocr,
                    CACHED_OCR_TAP_TOLERANCE_DP, RANGE_TOLERANCE_DP);
            if (cachedHit) {
                Rect fullRoi = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
                DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "indexed hit gesture="
                        + gesture.kind + " source=IMAGE_OCR"
                        + " chars=" + ocr.chars().size()
                        + " cachedHit=true localFallback=false"
                        + " preIndex=true coordinateSpace=SCREEN");
                callback.onResolved(new Result(Source.IMAGE_OCR, ocr,
                        gestureScreen, fullRoi, null));
                return;
            }
        }

        DiagnosticLog.i(state.app, "G_CIRCLE_TEXT_RESOLVE", "cached miss gesture="
                + gesture.kind
                + " cachedOcrAvailable=" + usable(ocr)
                + " -> local enhanced pixel OCR");
        resolveLocalEnhancedOcr(state, frame, gesture, gestureScreen, callback);
    }

    /**
     * Miss fallback that only re-examines screenshot pixels around the gesture. The screenshot is
     * cropped exactly once; scaling/contrast variants are owned by CircleMultiScaleOcr and returned
     * to this crop's native coordinate plane before the shared frame transform maps them to SCREEN.
     */
    private static void resolveLocalEnhancedOcr(PreloadState state,
                                                GoogleCircleCapture.Frame frame,
                                                GoogleCircleSelection.Selection gesture,
                                                Rect gestureScreen,
                                                Callback callback) {
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, new Rect(),
                    new IllegalStateException("frozen screenshot unavailable")));
            return;
        }

        Rect roi = localRecognitionRoi(state.app, frame, gesture);
        if (roi.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, state.ocrError));
            return;
        }

        final Bitmap crop;
        try {
            Bitmap made = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
            if (made == source) {
                made = source.copy(Bitmap.Config.ARGB_8888, false);
                if (made == null) throw new IllegalStateException("local OCR crop copy failed");
            }
            crop = made;
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, t));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "start gesture=" + gesture.kind
                + " roi=" + roi.toShortString()
                + " input=" + crop.getWidth() + "x" + crop.getHeight()
                + " enhancement=internal_multiscale"
                + " geometry=roi_to_screen_matrix"
                + " enginePolicy=follow_main_setting"
                + " pixelOnly=true semanticLabels=false nearbyCaptionSearch=false");

        CircleLocalOcrFallback.recognize(state.app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (!isCurrent(state, frame)) {
                    recycle(crop);
                    return;
                }
                OcrDocument screen = frame.transform.documentLocalToScreen(document, roi,
                        crop.getWidth(), crop.getHeight(), "local-enhanced-");
                recycle(crop);
                boolean hit = documentHitsGesture(state.app, frame, gesture, screen,
                        LOCAL_OCR_TAP_TOLERANCE_DP, RANGE_TOLERANCE_DP);
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "done gesture=" + gesture.kind
                        + " hit=" + hit
                        + " chars=" + (screen == null ? 0 : screen.chars().size())
                        + " text=" + summarize(screen == null ? "" : screen.fullText())
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                        + " coordinateSpace=SCREEN pixelOnly=true");
                callback.onResolved(new Result(hit ? Source.IMAGE_OCR : Source.NONE,
                        hit ? screen : null, gestureScreen, roi, null));
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "failed gesture="
                        + gesture.kind + " error=" + ScreenCaptureBackend.safeMessage(error));
                callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, error));
            }
        });
    }

    /** Build OCR search regions in SCREEN space first, then map them to screenshot pixels once. */
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

    private static boolean documentHitsGesture(Context app,
                                               GoogleCircleCapture.Frame frame,
                                               GoogleCircleSelection.Selection gesture,
                                               OcrDocument document,
                                               float tapToleranceDp,
                                               float rangeToleranceDp) {
        if (!usable(document) || frame == null || gesture == null) return false;

        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            PointF p = bitmapPointToScreen(frame, gesture.focus);
            float tolerance = dp(app, tapToleranceDp);
            float best = Float.MAX_VALUE;
            for (OcrDocument.CharUnit c : document.chars()) {
                if (c == null) continue;
                Rect r = c.bounds();
                if (r.isEmpty()) continue;
                if (r.contains(Math.round(p.x), Math.round(p.y))) return true;
                float dx = p.x < r.left ? r.left - p.x : p.x > r.right ? p.x - r.right : 0f;
                float dy = p.y < r.top ? r.top - p.y : p.y > r.bottom ? p.y - r.bottom : 0f;
                best = Math.min(best, (float) Math.hypot(dx, dy));
            }
            return best <= tolerance;
        }

        Rect region = gestureScreenBounds(frame, gesture);
        if (region.isEmpty()) return false;
        int tolerance = Math.max(1, Math.round(dp(app, rangeToleranceDp)));
        Rect tolerant = new Rect(region);
        tolerant.inset(-tolerance, -tolerance);
        if (!tolerant.intersect(frame.screenBounds)) return false;
        for (OcrDocument.CharUnit c : document.chars()) {
            if (c == null) continue;
            Rect r = c.bounds();
            if (!r.isEmpty() && Rect.intersects(tolerant, r)) return true;
        }
        return false;
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
