package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gesture-local OCR recovery for small logos and image text missed by the frozen full-frame index.
 *
 * <p>The fast path examines the complete local ROI at several scales. Larger ROIs additionally use
 * overlapping tiles when the complete-ROI result is not sufficient by itself. Every OCR request is
 * routed through {@link OcrEngine#recognizeDocument(Context, Bitmap, OcrEngine.DocumentCallback)},
 * so Auto / PP Medium / PP Small / ML Kit always follow the user's main OCR setting.</p>
 *
 * <p>Tile/scale geometry is normalized back to the original ROI with {@link CoordinateMapper}
 * before results are merged. No caller-visible coordinate is inferred from a requested scale.</p>
 */
final class CircleMultiScaleOcr {
    interface Callback {
        void onSuccess(OcrDocument document, float scaleX, float scaleY, String variant);
        void onFailure(Throwable error);
    }

    private static final int MAX_EDGE = 1024;
    private static final float[] SCALES = {1f, 2f, 3f, 4f};

    /** Tiny icon ROIs already benefit from multiscale; tiling them would cut the logo apart. */
    private static final int TILE_TRIGGER_EDGE_PX = 320;
    private static final float TILE_FRACTION = 0.68f;
    private static final int TILE_TARGET_MIN_EDGE_PX = 480;
    private static final float TILE_MIN_SCALE = 1.5f;
    private static final float TILE_MAX_SCALE = 3f;

    private static final class Candidate {
        final OcrDocument document;
        final float scaleX;
        final float scaleY;
        final String variant;
        final String key;

        Candidate(OcrDocument document, float scaleX, float scaleY,
                  String variant, String key) {
            this.document = document;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.variant = variant;
            this.key = key;
        }
    }

    private static final class TileSpec {
        final Rect bounds;
        final String name;

        TileSpec(Rect bounds, String name) {
            this.bounds = new Rect(bounds);
            this.name = name;
        }
    }

    private static final class DocumentSource {
        final OcrDocument document;
        final String sourceId;
        final boolean fullRoi;

        DocumentSource(OcrDocument document, String sourceId, boolean fullRoi) {
            this.document = document;
            this.sourceId = sourceId;
            this.fullRoi = fullRoi;
        }
    }

    private static final class LineCandidate {
        final OcrDocument.Line line;
        final String sourceId;
        final boolean fullRoi;
        final String key;

        LineCandidate(OcrDocument.Line line, String sourceId, boolean fullRoi) {
            this.line = line;
            this.sourceId = sourceId;
            this.fullRoi = fullRoi;
            this.key = normalize(line == null ? "" : line.text());
        }
    }

    private static final class LineCluster {
        final ArrayList<LineCandidate> members = new ArrayList<>();
        final Set<String> sources = new HashSet<>();
        boolean hasFull;

        void add(LineCandidate candidate) {
            members.add(candidate);
            sources.add(candidate.sourceId);
            hasFull |= candidate.fullRoi;
        }

        int support() { return sources.size(); }
    }

    private static final class Session {
        final Context app;
        final Bitmap source;
        final Callback callback;
        final ArrayList<Candidate> candidates = new ArrayList<>();
        final Map<String, Integer> votes = new HashMap<>();
        final ArrayList<DocumentSource> tileDocuments = new ArrayList<>();
        final List<TileSpec> tiles;
        final boolean tileEligible;

        int passIndex;
        int tileIndex;
        Throwable lastError;
        boolean finished;
        boolean tileStageStarted;

        Session(Context app, Bitmap source, Callback callback) {
            this.app = app;
            this.source = source;
            this.callback = callback;
            this.tiles = buildTiles(source.getWidth(), source.getHeight());
            this.tileEligible = !tiles.isEmpty();
        }

        void start() {
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "start strategy=main_setting_multiscale_overlap_tiles"
                            + " input=" + source.getWidth() + "x" + source.getHeight()
                            + " passes=1x,2x,3x,4x,contrast3x"
                            + " tiles=" + tiles.size()
                            + " tileTriggerEdgePx=" + TILE_TRIGGER_EDGE_PX
                            + " earlyConsensus=2");
            runNextFullPass();
        }

        private void runNextFullPass() {
            if (finished) return;
            if (passIndex >= SCALES.length + 1) {
                if (tileEligible) startTiles("full_passes_complete");
                else finishBestFull();
                return;
            }

            final boolean contrast = passIndex == SCALES.length;
            final float requestedScale = contrast ? 3f : SCALES[passIndex];
            final String variant = contrast
                    ? "contrast-3x" : String.format(Locale.ROOT, "%.0fx", requestedScale);
            passIndex++;

            final Bitmap input;
            try {
                input = makeVariant(source, requestedScale, contrast);
            } catch (Throwable t) {
                lastError = t;
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "variant prepare failed variant=" + variant + " error=" + safe(t));
                runNextFullPass();
                return;
            }

            final float scaleX = input.getWidth() / (float) Math.max(1, source.getWidth());
            final float scaleY = input.getHeight() / (float) Math.max(1, source.getHeight());
            final long started = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "pass start variant=" + variant
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight()
                            + " scale=" + String.format(Locale.ROOT, "%.2fx%.2f", scaleX, scaleY)
                            + " engine=main_setting");

            OcrEngine.recognizeDocument(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    recycleVariant(input);
                    if (finished) return;
                    String key = normalize(document == null ? "" : document.fullText());
                    boolean usable = document != null && !key.isEmpty() && !document.chars().isEmpty();
                    int vote = 0;
                    if (usable) {
                        Candidate candidate = new Candidate(document, scaleX, scaleY, variant, key);
                        candidates.add(candidate);
                        vote = votes.getOrDefault(key, 0) + 1;
                        votes.put(key, vote);
                    }
                    DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                            "pass done variant=" + variant
                                    + " usable=" + usable
                                    + " vote=" + vote
                                    + " engine=" + (document == null ? "none" : document.engine())
                                    + " chars=" + (document == null ? 0 : document.chars().size())
                                    + " text=" + summarize(document == null ? "" : document.fullText())
                                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));

                    if (usable && vote >= 2) {
                        // Tiny icon crops finish immediately. Larger gesture regions still get one
                        // overlap-tile stage so small text omitted from the complete ROI can recover.
                        if (tileEligible) startTiles("full_consensus");
                        else finishForKey(key, "consensus");
                    } else {
                        runNextFullPass();
                    }
                }

                @Override public void onFailure(Throwable error) {
                    recycleVariant(input);
                    if (finished) return;
                    lastError = error;
                    DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                            "pass failed variant=" + variant
                                    + " error=" + safe(error)
                                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                    runNextFullPass();
                }
            });
        }

        private void startTiles(String reason) {
            if (finished || tileStageStarted) return;
            tileStageStarted = true;
            DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                    "start reason=" + reason
                            + " tiles=" + tiles.size()
                            + " overlapFraction=" + TILE_FRACTION
                            + " enginePolicy=follow_main_setting"
                            + " coordinateSpace=local_roi");
            runNextTile();
        }

        private void runNextTile() {
            if (finished) return;
            if (tileIndex >= tiles.size()) {
                finishMerged();
                return;
            }

            final TileSpec tile = tiles.get(tileIndex++);
            final Bitmap crop;
            final Bitmap input;
            final float requestedScale;
            try {
                crop = Bitmap.createBitmap(source, tile.bounds.left, tile.bounds.top,
                        tile.bounds.width(), tile.bounds.height());
                int minEdge = Math.max(1, Math.min(crop.getWidth(), crop.getHeight()));
                requestedScale = Math.max(TILE_MIN_SCALE,
                        Math.min(TILE_MAX_SCALE, TILE_TARGET_MIN_EDGE_PX / (float) minEdge));
                input = makeVariant(crop, requestedScale, false);
            } catch (Throwable t) {
                lastError = t;
                DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                        "prepare failed tile=" + tile.name + " bounds=" + tile.bounds.toShortString()
                                + " error=" + safe(t));
                runNextTile();
                return;
            }

            final long started = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                    "pass start tile=" + tile.name
                            + " bounds=" + tile.bounds.toShortString()
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight()
                            + " requestedScale=" + String.format(Locale.ROOT, "%.2f", requestedScale));

            OcrEngine.recognizeDocument(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    if (!finished && usableBitmapDocument(document)) {
                        OcrDocument mapped = mapTileToSource(document, tile.bounds,
                                source.getWidth(), source.getHeight(), tile.name);
                        if (usableBitmapDocument(mapped)) {
                            tileDocuments.add(new DocumentSource(mapped, tile.name, false));
                        }
                        DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                                "pass done tile=" + tile.name
                                        + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                        + " text=" + summarize(mapped == null ? "" : mapped.fullText())
                                        + " engine=" + document.engine()
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));
                    }
                    recycleVariant(input);
                    if (crop != input) recycleVariant(crop);
                    runNextTile();
                }

                @Override public void onFailure(Throwable error) {
                    lastError = error;
                    DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                            "pass failed tile=" + tile.name
                                    + " error=" + safe(error)
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    recycleVariant(input);
                    if (crop != input) recycleVariant(crop);
                    runNextTile();
                }
            });
        }

        private void finishForKey(String key, String reason) {
            if (finished) return;
            Candidate best = null;
            for (Candidate candidate : candidates) {
                if (!candidate.key.equals(key)) continue;
                if (best == null || quality(candidate.document) > quality(best.document)) {
                    best = candidate;
                }
            }
            if (best == null) {
                finishBestFull();
                return;
            }
            finished = true;
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "finish reason=" + reason
                            + " variant=" + best.variant
                            + " votes=" + votes.getOrDefault(key, 0)
                            + " engine=" + best.document.engine()
                            + " text=" + summarize(best.document.fullText()));
            callback.onSuccess(best.document, best.scaleX, best.scaleY, best.variant);
        }

        private void finishBestFull() {
            if (finished) return;
            Candidate best = bestFullCandidate();
            finished = true;
            if (best != null) {
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "finish reason=best_available"
                                + " variant=" + best.variant
                                + " votes=" + votes.getOrDefault(best.key, 0)
                                + " engine=" + best.document.engine()
                                + " text=" + summarize(best.document.fullText()));
                callback.onSuccess(best.document, best.scaleX, best.scaleY, best.variant);
            } else {
                Throwable error = lastError == null
                        ? new IllegalStateException("multi-scale OCR empty") : lastError;
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "finish reason=empty error=" + safe(error));
                callback.onFailure(error);
            }
        }

        private void finishMerged() {
            if (finished) return;
            ArrayList<DocumentSource> documents = new ArrayList<>();
            Candidate full = bestFullCandidate();
            if (full != null) {
                OcrDocument mappedFull = mapFullToSource(full, source.getWidth(), source.getHeight());
                if (usableBitmapDocument(mappedFull)) {
                    documents.add(new DocumentSource(mappedFull, "full-" + full.variant, true));
                }
            }
            documents.addAll(tileDocuments);

            OcrDocument merged = mergeDocuments(documents, source.getWidth(), source.getHeight());
            finished = true;
            if (usableBitmapDocument(merged)) {
                DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                        "finish merged=true sources=" + documents.size()
                                + " tileSources=" + tileDocuments.size()
                                + " lines=" + merged.lines().size()
                                + " chars=" + merged.chars().size()
                                + " text=" + summarize(merged.fullText()));
                // Merged geometry is already in the original local-ROI bitmap coordinates.
                callback.onSuccess(merged, 1f, 1f, "overlap-tiles");
            } else if (full != null) {
                // Never make the tile enhancement worse than the previous multiscale result.
                DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                        "finish merged=false fallback=best_full variant=" + full.variant);
                callback.onSuccess(full.document, full.scaleX, full.scaleY, full.variant);
            } else {
                Throwable error = lastError == null
                        ? new IllegalStateException("tile OCR empty") : lastError;
                callback.onFailure(error);
            }
        }

        private Candidate bestFullCandidate() {
            Candidate best = null;
            int bestVotes = -1;
            double bestQuality = Double.NEGATIVE_INFINITY;
            for (Candidate candidate : candidates) {
                int count = votes.getOrDefault(candidate.key, 0);
                double q = quality(candidate.document);
                if (count > bestVotes || (count == bestVotes && q > bestQuality)) {
                    best = candidate;
                    bestVotes = count;
                    bestQuality = q;
                }
            }
            return best;
        }
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        new Session(context.getApplicationContext(), bitmap, callback).start();
    }

    private static List<TileSpec> buildTiles(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (Math.max(w, h) < TILE_TRIGGER_EDGE_PX) return List.of();

        int tw = Math.max(64, Math.min(w, Math.round(w * TILE_FRACTION)));
        int th = Math.max(64, Math.min(h, Math.round(h * TILE_FRACTION)));
        if (tw >= w && th >= h) return List.of();

        ArrayList<TileSpec> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addTile(out, seen, new Rect(0, 0, tw, th), "tl");
        addTile(out, seen, new Rect(w - tw, 0, w, th), "tr");
        addTile(out, seen, new Rect(0, h - th, tw, h), "bl");
        addTile(out, seen, new Rect(w - tw, h - th, w, h), "br");
        return List.copyOf(out);
    }

    private static void addTile(List<TileSpec> out, Set<String> seen, Rect bounds, String name) {
        if (bounds == null || bounds.isEmpty()) return;
        String key = bounds.flattenToString();
        if (seen.add(key)) out.add(new TileSpec(bounds, name));
    }

    private static Bitmap makeVariant(Bitmap source, float requestedScale, boolean contrast) {
        int sw = Math.max(1, source.getWidth());
        int sh = Math.max(1, source.getHeight());
        float maxScale = MAX_EDGE / (float) Math.max(sw, sh);
        float scale = Math.max(1f, Math.min(requestedScale, maxScale));
        int width = Math.max(1, Math.round(sw * scale));
        int height = Math.max(1, Math.round(sh * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (!contrast) {
            if (scaled == source) {
                Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) throw new IllegalStateException("OCR variant copy failed");
                return copy;
            }
            return scaled;
        }

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        float c = 1.8f;
        float offset = 128f * (1f - c);
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                c, 0, 0, 0, offset,
                0, c, 0, 0, offset,
                0, 0, c, 0, offset,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(contrastMatrix);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(scaled, 0f, 0f, paint);
        if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        return out;
    }

    private static OcrDocument mapFullToSource(Candidate candidate, int width, int height) {
        if (candidate == null || candidate.document == null) return null;
        OcrDocument document = candidate.document;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, document.imageWidth(), document.imageHeight()),
                new RectF(0f, 0f, Math.max(1, width), Math.max(1, height)));
        return mapper.mapDocument(document, false, Math.max(1, width), Math.max(1, height),
                "full-" + candidate.variant + "-");
    }

    private static OcrDocument mapTileToSource(OcrDocument document, Rect tile,
                                               int width, int height, String tileName) {
        if (document == null || tile == null || tile.isEmpty()) return null;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, document.imageWidth(), document.imageHeight()),
                new RectF(tile));
        return mapper.mapDocument(document, false, Math.max(1, width), Math.max(1, height),
                "tile-" + tileName + "-");
    }

    /** Spatially de-duplicates overlapping full/tile lines after every source is in one ROI space. */
    private static OcrDocument mergeDocuments(List<DocumentSource> documents, int width, int height) {
        if (documents == null || documents.isEmpty()) return null;

        ArrayList<LineCandidate> candidates = new ArrayList<>();
        boolean hasFull = false;
        for (DocumentSource source : documents) {
            if (source == null || !usableBitmapDocument(source.document)) continue;
            hasFull |= source.fullRoi;
            for (OcrDocument.Line line : source.document.lines()) {
                if (line == null || line.bounds().isEmpty() || normalize(line.text()).isEmpty()) continue;
                candidates.add(new LineCandidate(line, source.sourceId, source.fullRoi));
            }
        }
        if (candidates.isEmpty()) return null;

        // Full ROI is considered first for stable reading order; overlap clusters then add tile
        // support. The chosen representative can still be a tile line if its confidence is better.
        candidates.sort(Comparator
                .comparing((LineCandidate c) -> !c.fullRoi)
                .thenComparingInt(c -> c.line.bounds().top)
                .thenComparingInt(c -> c.line.bounds().left));

        ArrayList<LineCluster> clusters = new ArrayList<>();
        for (LineCandidate candidate : candidates) {
            LineCluster match = null;
            for (LineCluster cluster : clusters) {
                if (cluster.members.isEmpty()) continue;
                LineCandidate representative = cluster.members.get(0);
                if (!candidate.key.equals(representative.key)) continue;
                if (overlapRatio(candidate.line.bounds(), representative.line.bounds()) >= 0.55f) {
                    match = cluster;
                    break;
                }
            }
            if (match == null) {
                match = new LineCluster();
                clusters.add(match);
            }
            match.add(candidate);
        }

        ArrayList<OcrDocument.Line> kept = new ArrayList<>();
        for (LineCluster cluster : clusters) {
            // When a complete-ROI result exists, a tile-only line needs confirmation from at least
            // two overlapping sources. If complete ROI found no text at all, one good tile may be
            // the only recovery path, so tile-only clusters are retained.
            if (hasFull && !cluster.hasFull && cluster.support() < 2) continue;
            LineCandidate best = null;
            for (LineCandidate member : cluster.members) {
                if (best == null || lineQuality(member) > lineQuality(best)) best = member;
            }
            if (best != null) kept.add(best.line);
        }
        if (kept.isEmpty()) return null;

        kept.sort((a, b) -> {
            Rect ar = a.bounds();
            Rect br = b.bounds();
            int rowTolerance = Math.max(3,
                    Math.min(Math.max(1, ar.height()), Math.max(1, br.height())) / 2);
            int dy = ar.centerY() - br.centerY();
            if (Math.abs(dy) > rowTolerance) return Integer.compare(ar.centerY(), br.centerY());
            return Integer.compare(ar.left, br.left);
        });

        ArrayList<OcrDocument.Line> rebuilt = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        int lineId = 0;
        int globalGroup = 0;
        int order = 0;
        float confidenceSum = 0f;

        for (OcrDocument.Line line : kept) {
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            int previousSourceGroup = Integer.MIN_VALUE;
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
                if (previousSourceGroup != Integer.MIN_VALUE && c.group() != previousSourceGroup) {
                    globalGroup++;
                }
                chars.add(new OcrDocument.CharUnit(c.text(), c.bounds(), c.confidence(),
                        lineId, globalGroup, order++));
                previousSourceGroup = c.group();
            }
            if (chars.isEmpty()) continue;
            Rect bounds = new Rect(chars.get(0).bounds());
            for (int i = 1; i < chars.size(); i++) bounds.union(chars.get(i).bounds());
            String text = line.text();
            rebuilt.add(new OcrDocument.Line(text, bounds, line.confidence(), chars));
            blocks.add(text);
            if (fullText.length() > 0) fullText.append('\n');
            fullText.append(text);
            confidenceSum += line.confidence();
            lineId++;
            globalGroup++;
        }
        if (rebuilt.isEmpty()) return null;

        float confidence = confidenceSum / rebuilt.size();
        return new OcrDocument(fullText.toString(), blocks, rebuilt,
                "multiscale-overlap-tiles", confidence,
                rebuilt.size() * 10d + confidence * 100d,
                Math.max(1, width), Math.max(1, height));
    }

    private static float overlapRatio(Rect a, Rect b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        Rect intersection = new Rect();
        if (!intersection.setIntersect(a, b)) return 0f;
        long overlap = Math.max(0L, (long) intersection.width() * intersection.height());
        long smaller = Math.max(1L, Math.min((long) a.width() * a.height(),
                (long) b.width() * b.height()));
        return overlap / (float) smaller;
    }

    private static double lineQuality(LineCandidate candidate) {
        if (candidate == null || candidate.line == null) return Double.NEGATIVE_INFINITY;
        OcrDocument.Line line = candidate.line;
        String key = normalize(line.text());
        // Tile results get a small tie-breaker because they were recognized at a tighter scale, but
        // confidence and non-empty text dominate the choice.
        return line.confidence() * 1000d
                + Math.min(96, key.codePointCount(0, key.length()))
                + (candidate.fullRoi ? 0d : 1d);
    }

    private static boolean usableBitmapDocument(OcrDocument document) {
        return document != null && document.isBitmapSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static void recycleVariant(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static double quality(OcrDocument document) {
        if (document == null) return Double.NEGATIVE_INFINITY;
        String key = normalize(document.fullText());
        return document.score() + document.confidence() * 100.0
                + Math.min(64, key.codePointCount(0, key.length()));
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || isCjk(cp)) out.appendCodePoint(cp);
        });
        return out.toString();
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static String summarize(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 48 ? oneLine : oneLine.substring(0, 48) + "…";
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CircleMultiScaleOcr() {}
}
