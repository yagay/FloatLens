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
 * View text and full-frame OCR are prepared once when Circle opens. Gestures first hit-test that
 * cache. If the cached full-frame OCR misses the actual gesture, a small pixel-only ROI around the
 * gesture is enlarged and OCR'd again. The fallback intentionally uses only screenshot pixels: no
 * Accessibility label, contentDescription, package/app name or nearby semantic mapping is involved.
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

    // TAP fallback deliberately stays tight around the touched icon/image. This is not a search for
    // nearby captions; the goal is to let OCR re-examine the image pixels at a much larger scale.
    private static final float LOCAL_TAP_HALF_SIZE_DP = 38f; // 76dp square
    private static final float LOCAL_RANGE_PAD_DP = 4f;
    private static final int LOCAL_TARGET_MIN_EDGE_PX = 384;
    private static final int LOCAL_MAX_EDGE_PX = 1024;
    private static final float LOCAL_MIN_SCALE = 2f;
    private static final float LOCAL_MAX_SCALE = 4f;

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
                + " localPixelOnly=true"
                + " coordinateSpace=SCREEN");

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
     * Miss fallback that only re-examines screenshot pixels around the gesture. TAP uses a 76dp
     * square so launcher captions/nearby text are not intentionally searched. The crop is enlarged
     * 2-4x before OCR, restoring the old local-ROI advantage for tiny logos and stylized icon text.
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
        final Bitmap enlarged;
        final float scaleX;
        final float scaleY;
        try {
            crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
            int[] target = localEnhancedSize(crop.getWidth(), crop.getHeight());
            Bitmap made = Bitmap.createScaledBitmap(crop, target[0], target[1], true);
            if (made == null) throw new IllegalStateException("local OCR upscale failed");
            enlarged = made;
            scaleX = enlarged.getWidth() / (float) Math.max(1, roi.width());
            scaleY = enlarged.getHeight() / (float) Math.max(1, roi.height());
            if (crop != enlarged) recycle(crop);
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, t));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "start gesture=" + gesture.kind
                + " roi=" + roi.toShortString()
                + " input=" + roi.width() + "x" + roi.height()
                + " enlarged=" + enlarged.getWidth() + "x" + enlarged.getHeight()
                + " scale=" + String.format(java.util.Locale.ROOT, "%.2fx%.2f", scaleX, scaleY)
                + " pixelOnly=true semanticLabels=false nearbyCaptionSearch=false");

        OcrEngine.recognizeDocument(state.app, enlarged, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (!isCurrent(state, frame)) {
                    recycle(enlarged);
                    return;
                }
                OcrDocument screen = translateScaledToScreen(frame, roi, document, scaleX, scaleY);
                recycle(enlarged);
                boolean hit = documentHitsGesture(state.app, frame, gesture, screen,
                        LOCAL_OCR_TAP_TOLERANCE_DP, RANGE_TOLERANCE_DP);
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "done gesture=" + gesture.kind
                        + " hit=" + hit
                        + " chars=" + (screen == null ? 0 : screen.chars().size())
                        + " text=" + summarize(screen == null ? "" : screen.fullText())
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                        + " pixelOnly=true");
                callback.onResolved(new Result(hit ? Source.IMAGE_OCR : Source.NONE,
                        hit ? screen : null, gestureScreen, roi, null));
            }

            @Override public void onFailure(Throwable error) {
                recycle(enlarged);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "failed gesture="
                        + gesture.kind + " error=" + ScreenCaptureBackend.safeMessage(error));
                callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, error));
            }
        });
    }

    private static Rect localRecognitionRoi(Context app, GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        int bw = frame.bitmap.getWidth();
        int bh = frame.bitmap.getHeight();
        if (bw <= 0 || bh <= 0) return new Rect();

        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            float halfX = bitmapPxForDp(app, frame, LOCAL_TAP_HALF_SIZE_DP, true);
            float halfY = bitmapPxForDp(app, frame, LOCAL_TAP_HALF_SIZE_DP, false);
            Rect roi = new Rect(
                    (int) Math.floor(gesture.focus.x - halfX),
                    (int) Math.floor(gesture.focus.y - halfY),
                    (int) Math.ceil(gesture.focus.x + halfX),
                    (int) Math.ceil(gesture.focus.y + halfY));
            return clampBitmapRect(roi, bw, bh);
        }

        Rect exact = GoogleCircleSelection.exactRectAndClamp(gesture.bounds, bw, bh);
        if (exact.isEmpty()) return exact;
        int padX = Math.max(1, Math.round(bitmapPxForDp(app, frame, LOCAL_RANGE_PAD_DP, true)));
        int padY = Math.max(1, Math.round(bitmapPxForDp(app, frame, LOCAL_RANGE_PAD_DP, false)));
        Rect roi = new Rect(exact.left - padX, exact.top - padY,
                exact.right + padX, exact.bottom + padY);
        return clampBitmapRect(roi, bw, bh);
    }

    private static int[] localEnhancedSize(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        float scale = Math.max(LOCAL_MIN_SCALE, LOCAL_TARGET_MIN_EDGE_PX / (float) Math.max(1, min));
        scale = Math.min(LOCAL_MAX_SCALE, scale);
        if (max * scale > LOCAL_MAX_EDGE_PX) {
            scale = Math.max(1f, LOCAL_MAX_EDGE_PX / (float) max);
        }
        return new int[]{Math.max(32, Math.round(w * scale)),
                Math.max(32, Math.round(h * scale))};
    }

    private static Rect clampBitmapRect(Rect source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return new Rect();
        Rect r = new Rect(source);
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.top < 0) r.offset(0, -r.top);
        if (r.right > width) r.offset(width - r.right, 0);
        if (r.bottom > height) r.offset(0, height - r.bottom);
        r.left = Math.max(0, Math.min(width - 1, r.left));
        r.top = Math.max(0, Math.min(height - 1, r.top));
        r.right = Math.max(r.left + 1, Math.min(width, r.right));
        r.bottom = Math.max(r.top + 1, Math.min(height, r.bottom));
        return r;
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

    private static OcrDocument translateToScreen(GoogleCircleCapture.Frame frame, Rect roi,
                                                 OcrDocument local) {
        return translateScaledToScreen(frame, roi, local, 1f, 1f);
    }

    private static OcrDocument translateScaledToScreen(GoogleCircleCapture.Frame frame, Rect roi,
                                                        OcrDocument local,
                                                        float scaleX, float scaleY) {
        String prefix = (scaleX == 1f && scaleY == 1f) ? "preindex-" : "local-enhanced-";
        if (local == null || local.lines().isEmpty()) {
            return OcrDocument.screenSpace(local == null ? "" : local.fullText(),
                    local == null ? List.of() : local.blocks(), List.of(),
                    local == null ? prefix + "ocr" : prefix + local.engine(),
                    local == null ? 0f : local.confidence(),
                    local == null ? 0d : local.score(),
                    Math.max(1, frame.screenBounds.width()),
                    Math.max(1, frame.screenBounds.height()));
        }

        float sx = Math.max(0.0001f, scaleX);
        float sy = Math.max(0.0001f, scaleY);
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int order = 0;
        for (OcrDocument.Line line : local.lines()) {
            Rect lineBitmap = scaledLocalToBitmap(line.bounds(), roi, sx, sy,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
            if (lineBitmap.isEmpty()) continue;
            Rect lineScreen = frame.bitmapRectToScreen(lineBitmap);
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null) continue;
                Rect charBitmap = scaledLocalToBitmap(c.bounds(), roi, sx, sy,
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
                prefix + local.engine(), local.confidence(), local.score(),
                Math.max(1, frame.screenBounds.width()),
                Math.max(1, frame.screenBounds.height()));
    }

    private static Rect scaledLocalToBitmap(Rect local, Rect roi, float scaleX, float scaleY,
                                            int width, int height) {
        if (local == null || local.isEmpty()) return new Rect();
        Rect out = new Rect(
                roi.left + (int) Math.floor(local.left / scaleX),
                roi.top + (int) Math.floor(local.top / scaleY),
                roi.left + (int) Math.ceil(local.right / scaleX),
                roi.top + (int) Math.ceil(local.bottom / scaleY));
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

    private static float bitmapPxForDp(Context app, GoogleCircleCapture.Frame frame,
                                       float valueDp, boolean horizontal) {
        float screenPx = dp(app, valueDp);
        if (horizontal) {
            return screenPx * frame.bitmap.getWidth()
                    / (float) Math.max(1, frame.screenBounds.width());
        }
        return screenPx * frame.bitmap.getHeight()
                / (float) Math.max(1, frame.screenBounds.height());
    }

    private static float dp(Context app, float value) {
        return value * app.getResources().getDisplayMetrics().density;
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
