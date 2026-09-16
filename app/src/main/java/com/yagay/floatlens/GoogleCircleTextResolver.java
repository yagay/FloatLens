package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screenshot-only text resolver for the Google-style Circle workspace.
 *
 * <p>Interactive gestures always prefer already cached OCR and otherwise OCR only a tight ROI from
 * the frozen screenshot. A two-lane overlapping tile index is built asynchronously in the
 * background and is never awaited by a gesture. All caches live only for the frozen screenshot
 * session and are released with the workspace.</p>
 */
final class GoogleCircleTextResolver {
    enum Source { VIEW, VIEW_OCR, IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        /** SCREEN-space OCR document used by the selection surface. */
        final OcrDocument document;
        /** Gesture-scoped subset used only to initialize selection start/end. */
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
            this.bitmapRoi = bitmapRoi == null ? new Rect() : new Rect(bitmapRoi);
            this.screenDocument = screenDocument;
        }
    }

    private static final class PreloadState {
        final long generation;
        final Context app;
        final WeakReference<GoogleCircleCapture.Frame> frameRef;
        final ArrayList<CachedRegion> regionCache = new ArrayList<>();
        final ArrayList<OcrDocument> tileDocuments = new ArrayList<>();
        final ArrayList<Rect> tileRois = new ArrayList<>();

        OcrDocument screenDocument;
        Throwable ocrError;
        int nextTileIndex;
        int completedTiles;
        int failedTiles;

        PreloadState(long generation, Context app, GoogleCircleCapture.Frame frame) {
            this.generation = generation;
            this.app = app;
            this.frameRef = new WeakReference<>(frame);
        }
    }

    private static final int TILE_CONCURRENCY = 2;
    private static final float TILE_OVERLAP_RATIO = 0.10f;
    private static final int REGION_CACHE_MAX = 10;
    private static final float REGION_CACHE_OVERLAP_MIN = 0.68f;

    private static final ExecutorService INDEX_IO = Executors.newFixedThreadPool(
            TILE_CONCURRENCY, r -> {
                Thread t = new Thread(r, "FloatLens-circle-tile-ocr");
                t.setDaemon(true);
                return t;
            });

    private static final Object INDEX_LOCK = new Object();
    private static final float CACHED_OCR_TAP_TOLERANCE_DP = 10f;
    private static final float RANGE_CORRIDOR_DP = 10f;
    private static final float LOCAL_TAP_HALF_WIDTH_DP = 92f;
    private static final float LOCAL_TAP_HALF_HEIGHT_DP = 42f;
    private static final float LOCAL_RANGE_PAD_X_DP = 18f;
    private static final float LOCAL_RANGE_PAD_Y_DP = 16f;
    private static final float LOCAL_MIN_WIDTH_DP = 96f;
    private static final float LOCAL_MIN_HEIGHT_DP = 44f;

    private static long indexGeneration;
    private static PreloadState currentIndex;

    static void preload(Context c, GoogleCircleCapture.Frame frame) {
        if (c == null || frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) return;
        Context app = c.getApplicationContext();
        PreloadState state;
        synchronized (INDEX_LOCK) {
            if (sameFrame(currentIndex, frame)) return;
            if (currentIndex != null) clearStateLocked(currentIndex);
            state = new PreloadState(++indexGeneration, app, frame);
            state.tileRois.addAll(buildTileRois(frame.bitmap.getWidth(), frame.bitmap.getHeight()));
            currentIndex = state;
        }

        DiagnosticLog.i(app, "G_CIRCLE_TEXT_INDEX", "start generation=" + state.generation
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " strategy=roi_cache_plus_overlapping_tiles"
                + " textOwner=IMAGE_OCR_ONLY"
                + " foreground=gesture_local_mlkit"
                + " background=two_lane_tile_mlkit"
                + " tiles=" + state.tileRois.size()
                + " tileConcurrency=" + TILE_CONCURRENCY
                + " tileOverlap=" + TILE_OVERLAP_RATIO
                + " regionCache=true cacheScope=frozen_frame"
                + " preindexBlocking=false ppocrPreindex=false viewText=false"
                + " geometry=shared_matrix_transform coordinateSpace=SCREEN");

        for (int lane = 0; lane < TILE_CONCURRENCY; lane++) {
            final int laneId = lane;
            INDEX_IO.execute(() -> startNextTile(state, frame, laneId));
        }
    }

    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (c == null || frame == null || gesture == null || callback == null) return;
        Context app = c.getApplicationContext();
        preload(app, frame);

        PreloadState state;
        OcrDocument indexed;
        Throwable indexError;
        synchronized (INDEX_LOCK) {
            state = sameFrame(currentIndex, frame) ? currentIndex : null;
            if (state == null) {
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreenBounds(frame, gesture), new Rect(),
                        new IllegalStateException("screenshot OCR state unavailable"), true));
                return;
            }
            indexed = state.screenDocument;
            indexError = state.ocrError;
        }

        Rect desiredRoi = localBitmapRoi(state.app, frame, gesture);
        CachedRegion region = findCachedRegion(state, desiredRoi, frame, gesture);
        if (region != null && usable(region.screenDocument)) {
            OcrDocument initial = selectGesture(state.app, frame, gesture, region.screenDocument);
            if (!usable(initial) && gesture.kind == GoogleCircleSelection.Kind.TAP) {
                initial = nearestGroupDocument(region.screenDocument,
                        frame.bitmapPointToScreen(gesture.focus.x, gesture.focus.y));
            }
            if (usable(initial)) {
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "region cache hit gesture="
                        + gesture.kind
                        + " roi=" + region.bitmapRoi.toShortString()
                        + " documentChars=" + region.screenDocument.chars().size()
                        + " initialChars=" + initial.chars().size()
                        + " text=" + summarize(initial.fullText())
                        + " engine=" + region.screenDocument.engine()
                        + " cache=frozen_frame_region");
                callback.onResolved(new Result(Source.IMAGE_OCR, region.screenDocument, initial,
                        gestureScreenBounds(frame, gesture), region.bitmapRoi, null, false));
                return;
            }
        }

        if (usable(indexed)) {
            OcrDocument initial = selectGesture(state.app, frame, gesture, indexed);
            if (usable(initial)) {
                Rect fullBitmap = new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "tile index hit gesture="
                        + gesture.kind
                        + " documentChars=" + indexed.chars().size()
                        + " initialChars=" + initial.chars().size()
                        + " text=" + summarize(initial.fullText())
                        + " engine=" + indexed.engine()
                        + " completedTiles=" + tileCompleted(state)
                        + " localFallback=false coordinateSpace=SCREEN");
                callback.onResolved(new Result(Source.IMAGE_OCR, indexed, initial,
                        gestureScreenBounds(frame, gesture), fullBitmap, null, false));
                return;
            }
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "tile index miss gesture="
                    + gesture.kind + " -> local screenshot OCR"
                    + " documentChars=" + indexed.chars().size()
                    + " completedTiles=" + tileCompleted(state)
                    + " engine=" + indexed.engine());
        } else {
            DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "background index not ready gesture="
                    + gesture.kind + " -> local screenshot OCR"
                    + " completedTiles=" + tileCompleted(state)
                    + (indexError == null ? "" : " indexError=" + safe(indexError)));
        }

        resolveLocalScreenshot(state, frame, gesture, desiredRoi, callback);
    }

    static void release(Context c, GoogleCircleCapture.Frame frame, String reason) {
        if (frame == null) return;
        Context app = c == null ? null : c.getApplicationContext();
        PreloadState released;
        int chars;
        int lines;
        int regions;
        int tiles;
        synchronized (INDEX_LOCK) {
            if (!sameFrame(currentIndex, frame)) return;
            released = currentIndex;
            chars = released.screenDocument == null ? 0 : released.screenDocument.chars().size();
            lines = released.screenDocument == null ? 0 : released.screenDocument.lines().size();
            regions = released.regionCache.size();
            tiles = released.completedTiles;
            currentIndex = null;
            indexGeneration++;
            clearStateLocked(released);
        }
        Context logContext = app == null ? released.app : app;
        DiagnosticLog.i(logContext, "G_CIRCLE_TEXT_INDEX", "release generation="
                + released.generation
                + " reason=" + (reason == null ? "unknown" : reason)
                + " chars=" + chars + " lines=" + lines
                + " cachedRegions=" + regions + " completedTiles=" + tiles);
    }

    private static void clearStateLocked(PreloadState state) {
        if (state == null) return;
        state.screenDocument = null;
        state.ocrError = null;
        state.regionCache.clear();
        state.tileDocuments.clear();
        state.tileRois.clear();
        state.nextTileIndex = 0;
        state.completedTiles = 0;
        state.failedTiles = 0;
    }

    private static void startNextTile(PreloadState state,
                                      GoogleCircleCapture.Frame frame,
                                      int laneId) {
        if (!isCurrent(state, frame)) return;

        final int tileIndex;
        final Rect roi;
        synchronized (INDEX_LOCK) {
            if (currentIndex != state || state.nextTileIndex >= state.tileRois.size()) return;
            tileIndex = state.nextTileIndex++;
            roi = new Rect(state.tileRois.get(tileIndex));
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(frame.bitmap, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            finishTile(state, frame, laneId, tileIndex, roi, null, t, 0L);
            INDEX_IO.execute(() -> startNextTile(state, frame, laneId));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_TILE_OCR", "start generation=" + state.generation
                + " lane=" + laneId
                + " tile=" + (tileIndex + 1) + "/" + state.tileRois.size()
                + " roi=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " overlap=" + TILE_OVERLAP_RATIO);

        CircleStableOcr.recognizeMlKit(state.app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument tileBitmapDocument) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument parentBitmap = tileBitmapDocument == null ? null
                            : tileBitmapDocument.translated(roi.left, roi.top,
                                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
                    OcrDocument screenDocument = parentBitmap == null ? null
                            : frame.transform.documentBitmapToScreen(parentBitmap);
                    finishTile(state, frame, laneId, tileIndex, roi,
                            screenDocument, null, started);
                } catch (Throwable t) {
                    finishTile(state, frame, laneId, tileIndex, roi,
                            null, t, started);
                } finally {
                    recycle(crop);
                }
                INDEX_IO.execute(() -> startNextTile(state, frame, laneId));
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (isCurrent(state, frame)) {
                    finishTile(state, frame, laneId, tileIndex, roi,
                            null, error, started);
                    INDEX_IO.execute(() -> startNextTile(state, frame, laneId));
                }
            }
        });
    }

    private static void finishTile(PreloadState state,
                                   GoogleCircleCapture.Frame frame,
                                   int laneId,
                                   int tileIndex,
                                   Rect roi,
                                   OcrDocument screenDocument,
                                   Throwable error,
                                   long started) {
        OcrDocument merged;
        int completed;
        int failed;
        int total;
        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return;
            if (usable(screenDocument)) state.tileDocuments.add(screenDocument);
            if (error != null) {
                state.failedTiles++;
                state.ocrError = error;
            }
            state.completedTiles++;
            state.screenDocument = mergeScreenDocuments(state.tileDocuments);
            merged = state.screenDocument;
            completed = state.completedTiles;
            failed = state.failedTiles;
            total = state.tileRois.size();
        }

        long elapsed = started <= 0L ? 0L : android.os.SystemClock.uptimeMillis() - started;
        DiagnosticLog.i(state.app, "G_CIRCLE_TILE_OCR", "finish generation=" + state.generation
                + " lane=" + laneId
                + " tile=" + (tileIndex + 1) + "/" + total
                + " roi=" + roi.toShortString()
                + " usable=" + usable(screenDocument)
                + " tileChars=" + (screenDocument == null ? 0 : screenDocument.chars().size())
                + " mergedChars=" + (merged == null ? 0 : merged.chars().size())
                + " completed=" + completed + "/" + total
                + " failed=" + failed
                + " elapsedMs=" + elapsed
                + (error == null ? "" : " error=" + safe(error)));
    }

    private static void resolveLocalScreenshot(PreloadState state,
                                               GoogleCircleCapture.Frame frame,
                                               GoogleCircleSelection.Selection gesture,
                                               Rect bitmapRoi,
                                               Callback callback) {
        if (!isCurrent(state, frame) || frame.bitmap == null || frame.bitmap.isRecycled()) return;
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        if (bitmapRoi == null || bitmapRoi.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreen, new Rect(), new IllegalStateException("empty local OCR ROI"), true));
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(frame.bitmap, bitmapRoi.left, bitmapRoi.top,
                    bitmapRoi.width(), bitmapRoi.height());
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, null,
                    gestureScreen, bitmapRoi, t, true));
            return;
        }

        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "start gesture=" + gesture.kind
                + " strategy=frozen_screenshot_direct_mlkit"
                + " roi=" + bitmapRoi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " cacheMiss=true ppocr=false preindexWait=false");

        CircleStableOcr.recognizeMlKit(state.app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument localBitmap) {
                try {
                    if (!isCurrent(state, frame)) return;
                    OcrDocument parentBitmap = localBitmap == null ? null
                            : localBitmap.translated(bitmapRoi.left, bitmapRoi.top,
                                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
                    OcrDocument localScreen = parentBitmap == null ? null
                            : frame.transform.documentBitmapToScreen(parentBitmap);
                    OcrDocument initial = selectGesture(state.app, frame, gesture, localScreen);
                    if (!usable(initial) && gesture.kind == GoogleCircleSelection.Kind.TAP) {
                        initial = nearestGroupDocument(localScreen,
                                frame.bitmapPointToScreen(gesture.focus.x, gesture.focus.y));
                    }

                    if (!usable(localScreen) || !usable(initial)) {
                        DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "empty after map gesture="
                                + gesture.kind + " engine="
                                + (localBitmap == null ? "none" : localBitmap.engine()));
                        callback.onResolved(new Result(Source.NONE, null, null,
                                gestureScreen, bitmapRoi,
                                new IllegalStateException("local screenshot OCR has no selectable text"), true));
                        return;
                    }

                    cacheRegion(state, bitmapRoi, localScreen);
                    DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "success gesture="
                            + gesture.kind
                            + " engine=" + localBitmap.engine()
                            + " documentChars=" + localScreen.chars().size()
                            + " selectedChars=" + initial.chars().size()
                            + " text=" + summarize(initial.fullText())
                            + " cacheStore=true"
                            + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                            + " coordinateSpace=SCREEN");
                    callback.onResolved(new Result(Source.IMAGE_OCR, localScreen, initial,
                            gestureScreen, bitmapRoi, null, true));
                } catch (Throwable t) {
                    callback.onResolved(new Result(Source.NONE, null, null,
                            gestureScreen, bitmapRoi, t, true));
                } finally {
                    recycle(crop);
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                if (!isCurrent(state, frame)) return;
                DiagnosticLog.i(state.app, "G_CIRCLE_LOCAL_OCR", "failed gesture="
                        + gesture.kind + " error=" + safe(error)
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                callback.onResolved(new Result(Source.NONE, null, null,
                        gestureScreen, bitmapRoi, error, true));
            }
        });
    }

    private static CachedRegion findCachedRegion(PreloadState state,
                                                 Rect desiredRoi,
                                                 GoogleCircleCapture.Frame frame,
                                                 GoogleCircleSelection.Selection gesture) {
        if (state == null || desiredRoi == null || desiredRoi.isEmpty()) return null;
        Rect gestureBitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        int focusX = Math.round(gesture.focus.x);
        int focusY = Math.round(gesture.focus.y);

        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return null;
            for (int i = state.regionCache.size() - 1; i >= 0; i--) {
                CachedRegion entry = state.regionCache.get(i);
                Rect cached = entry.bitmapRoi;
                boolean exactCoverage = cached.contains(desiredRoi);
                boolean enoughOverlap = overlapCoverage(desiredRoi, cached) >= REGION_CACHE_OVERLAP_MIN;
                boolean gestureCovered = gesture.kind == GoogleCircleSelection.Kind.TAP
                        ? cached.contains(focusX, focusY)
                        : overlapCoverage(gestureBitmap, cached) >= 0.95f;
                if ((exactCoverage || enoughOverlap) && gestureCovered && usable(entry.screenDocument)) {
                    state.regionCache.remove(i);
                    state.regionCache.add(entry);
                    return entry;
                }
            }
        }
        return null;
    }

    private static void cacheRegion(PreloadState state, Rect bitmapRoi, OcrDocument screenDocument) {
        if (state == null || bitmapRoi == null || bitmapRoi.isEmpty() || !usable(screenDocument)) return;
        synchronized (INDEX_LOCK) {
            if (currentIndex != state) return;
            for (int i = state.regionCache.size() - 1; i >= 0; i--) {
                CachedRegion existing = state.regionCache.get(i);
                if (existing.bitmapRoi.equals(bitmapRoi)
                        || overlapCoverage(bitmapRoi, existing.bitmapRoi) >= 0.90f) {
                    state.regionCache.remove(i);
                }
            }
            state.regionCache.add(new CachedRegion(bitmapRoi, screenDocument));
            while (state.regionCache.size() > REGION_CACHE_MAX) {
                state.regionCache.remove(0);
            }
        }
    }

    private static int tileCompleted(PreloadState state) {
        synchronized (INDEX_LOCK) {
            return currentIndex == state ? state.completedTiles : 0;
        }
    }

    private static ArrayList<Rect> buildTileRois(int width, int height) {
        ArrayList<Rect> out = new ArrayList<>();
        if (width <= 0 || height <= 0) return out;

        int columns = 2;
        int rows = height > width * 1.55f ? 3 : 2;
        int baseW = Math.max(1, (int) Math.ceil(width / (double) columns));
        int baseH = Math.max(1, (int) Math.ceil(height / (double) rows));
        int overlapX = Math.max(16, Math.round(baseW * TILE_OVERLAP_RATIO));
        int overlapY = Math.max(16, Math.round(baseH * TILE_OVERLAP_RATIO));

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int left = column * baseW;
                int top = row * baseH;
                int right = Math.min(width, left + baseW);
                int bottom = Math.min(height, top + baseH);
                if (column > 0) left = Math.max(0, left - overlapX);
                if (column < columns - 1) right = Math.min(width, right + overlapX);
                if (row > 0) top = Math.max(0, top - overlapY);
                if (row < rows - 1) bottom = Math.min(height, bottom + overlapY);
                Rect tile = new Rect(left, top, right, bottom);
                if (!tile.isEmpty()) out.add(tile);
            }
        }
        return out;
    }

    private static OcrDocument mergeScreenDocuments(List<OcrDocument> documents) {
        if (documents == null || documents.isEmpty()) return null;

        ArrayList<OcrDocument.Line> candidates = new ArrayList<>();
        int imageWidth = 1;
        int imageHeight = 1;
        float confidenceSum = 0f;
        int confidenceCount = 0;

        for (OcrDocument document : documents) {
            if (!usable(document)) continue;
            imageWidth = Math.max(imageWidth, document.imageWidth());
            imageHeight = Math.max(imageHeight, document.imageHeight());
            confidenceSum += document.confidence();
            confidenceCount++;
            candidates.addAll(document.lines());
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator
                .comparingInt((OcrDocument.Line line) -> line.bounds().top)
                .thenComparingInt(line -> line.bounds().left));

        ArrayList<OcrDocument.Line> accepted = new ArrayList<>();
        for (OcrDocument.Line candidate : candidates) {
            boolean duplicate = false;
            for (OcrDocument.Line existing : accepted) {
                if (samePhysicalLine(existing, candidate)) {
                    duplicate = true;
                    if (candidate.chars().size() > existing.chars().size()) {
                        int index = accepted.indexOf(existing);
                        accepted.set(index, candidate);
                    }
                    break;
                }
            }
            if (!duplicate) accepted.add(candidate);
        }

        accepted.sort(Comparator
                .comparingInt((OcrDocument.Line line) -> line.bounds().top)
                .thenComparingInt(line -> line.bounds().left));

        ArrayList<OcrDocument.Line> rebuilt = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        int order = 0;
        int lineIndex = 0;
        for (OcrDocument.Line sourceLine : accepted) {
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : sourceLine.chars()) {
                if (c.text().isBlank() || c.bounds().isEmpty()) continue;
                chars.add(new OcrDocument.CharUnit(c.text(), c.bounds(), c.confidence(),
                        lineIndex, c.group(), order++));
            }
            if (chars.isEmpty()) continue;
            String text = sourceLine.text() == null ? "" : sourceLine.text().trim();
            if (text.isEmpty()) {
                StringBuilder value = new StringBuilder();
                for (OcrDocument.CharUnit c : chars) value.append(c.text());
                text = value.toString().trim();
            }
            if (text.isEmpty()) continue;
            rebuilt.add(new OcrDocument.Line(text, sourceLine.bounds(),
                    sourceLine.confidence(), chars));
            blocks.add(text);
            if (fullText.length() > 0) fullText.append('\n');
            fullText.append(text);
            lineIndex++;
        }
        if (rebuilt.isEmpty()) return null;

        float confidence = confidenceCount == 0 ? 0f : confidenceSum / confidenceCount;
        return OcrDocument.screenSpace(fullText.toString(), blocks, rebuilt,
                "mlkit-circle-tiles", confidence, 0d, imageWidth, imageHeight);
    }

    private static boolean samePhysicalLine(OcrDocument.Line a, OcrDocument.Line b) {
        Rect ra = a.bounds();
        Rect rb = b.bounds();
        if (ra.isEmpty() || rb.isEmpty()) return false;
        float overlap = overlapCoverage(ra, rb);
        float reverse = overlapCoverage(rb, ra);
        float mutual = Math.min(overlap, reverse);
        if (mutual >= 0.72f) return true;
        String ta = normalizeText(a.text());
        String tb = normalizeText(b.text());
        return !ta.isEmpty() && ta.equals(tb) && mutual >= 0.35f;
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim();
    }

    private static float overlapCoverage(Rect target, Rect cover) {
        if (target == null || cover == null || target.isEmpty() || cover.isEmpty()) return 0f;
        int left = Math.max(target.left, cover.left);
        int top = Math.max(target.top, cover.top);
        int right = Math.min(target.right, cover.right);
        int bottom = Math.min(target.bottom, cover.bottom);
        if (right <= left || bottom <= top) return 0f;
        long intersection = (long) (right - left) * (bottom - top);
        long area = (long) target.width() * target.height();
        return area <= 0L ? 0f : Math.min(1f, intersection / (float) area);
    }

    private static OcrDocument selectGesture(Context app, GoogleCircleCapture.Frame frame,
                                             GoogleCircleSelection.Selection gesture,
                                             OcrDocument document) {
        if (!usable(document)) return null;
        return CircleGestureTextSelector.selectDocument(app, frame, gesture, document,
                CACHED_OCR_TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                "screenshot-gesture-selected");
    }

    private static Rect localBitmapRoi(Context app, GoogleCircleCapture.Frame frame,
                                       GoogleCircleSelection.Selection gesture) {
        int width = frame.bitmap.getWidth();
        int height = frame.bitmap.getHeight();
        if (width <= 0 || height <= 0) return new Rect();

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
        Rect expanded = new Rect(base.left - padX, base.top - padY,
                base.right + padX, base.bottom + padY);
        expanded = clampRect(expanded, width, height);
        int minW = bitmapPxForDp(app, frame, LOCAL_MIN_WIDTH_DP);
        int minH = bitmapPxForDp(app, frame, LOCAL_MIN_HEIGHT_DP);
        return ensureMinimumRect(expanded, width, height, minW, minH);
    }

    private static Rect ensureMinimumRect(Rect source, int width, int height, int minW, int minH) {
        if (source == null || source.isEmpty()) return new Rect();
        int targetW = Math.min(width, Math.max(source.width(), Math.max(1, minW)));
        int targetH = Math.min(height, Math.max(source.height(), Math.max(1, minH)));
        int left = source.centerX() - targetW / 2;
        int top = source.centerY() - targetH / 2;
        left = Math.max(0, Math.min(left, width - targetW));
        top = Math.max(0, Math.min(top, height - targetH));
        return new Rect(left, top, left + targetW, top + targetH);
    }

    private static Rect clampRect(Rect source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return new Rect();
        Rect out = new Rect(Math.max(0, source.left), Math.max(0, source.top),
                Math.min(width, source.right), Math.min(height, source.bottom));
        return out.isEmpty() ? new Rect() : out;
    }

    private static int bitmapPxForDp(Context app, GoogleCircleCapture.Frame frame, float dp) {
        float density = app.getResources().getDisplayMetrics().density;
        float screenPx = Math.max(1f, dp * density);
        return Math.max(1, Math.round(frame.transform.screenDistanceToBitmap(screenPx)));
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
        OcrDocument.Line line = new OcrDocument.Line(value, bounds,
                document.confidence(), chars);
        return OcrDocument.screenSpace(value, List.of(value), List.of(line),
                document.engine() + "+nearest-group", document.confidence(), document.score(),
                document.imageWidth(), document.imageHeight());
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
