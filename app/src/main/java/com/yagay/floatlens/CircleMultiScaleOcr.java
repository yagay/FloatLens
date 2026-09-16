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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Stable gesture-local OCR recovery for text/logos missed by the frozen multi-pass index.
 *
 * <p>The complete local ROI is tried at several scales through the user's main OCR setting. Every
 * successful result is immediately normalized back to the original ROI coordinate plane. Two
 * identical normalized results form an early consensus and finish the session. Overlap tiles are a
 * last-resort recovery path only when every complete-ROI pass is empty; tile results are never
 * globally merged or character-spliced.</p>
 */
final class CircleMultiScaleOcr {
    interface Callback {
        void onSuccess(OcrDocument document, float scaleX, float scaleY, String variant);
        void onFailure(Throwable error);
    }

    private static final int MAX_EDGE = 1024;
    private static final float[] SCALES = {1f, 2f, 3f, 4f};
    private static final int TILE_TRIGGER_EDGE_PX = 320;
    private static final float TILE_FRACTION = 0.68f;
    private static final int TILE_TARGET_MIN_EDGE_PX = 480;
    private static final float TILE_MIN_SCALE = 1.5f;
    private static final float TILE_MAX_SCALE = 3f;

    private static final class Candidate {
        final OcrDocument document;
        final float reportedScaleX;
        final float reportedScaleY;
        final String variant;
        final String key;
        final boolean tile;

        Candidate(OcrDocument document, float reportedScaleX, float reportedScaleY,
                  String variant, String key, boolean tile) {
            this.document = document;
            this.reportedScaleX = reportedScaleX;
            this.reportedScaleY = reportedScaleY;
            this.variant = variant;
            this.key = key;
            this.tile = tile;
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

    private static final class Session {
        final Context app;
        final Bitmap source;
        final Callback callback;
        final BooleanSupplier cancelled;
        final ArrayList<Candidate> fullCandidates = new ArrayList<>();
        final ArrayList<Candidate> tileCandidates = new ArrayList<>();
        final Map<String, Integer> fullVotes = new HashMap<>();
        final List<TileSpec> tiles;

        int passIndex;
        int tileIndex;
        Throwable lastError;
        boolean finished;
        boolean tileStageStarted;

        Session(Context app, Bitmap source, BooleanSupplier cancelled, Callback callback) {
            this.app = app;
            this.source = source;
            this.cancelled = cancelled;
            this.callback = callback;
            this.tiles = buildTiles(source.getWidth(), source.getHeight());
        }

        void start() {
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "start strategy=whole_roi_multiscale_consensus"
                            + " input=" + source.getWidth() + "x" + source.getHeight()
                            + " passes=1x,2x,3x,4x,contrast3x"
                            + " tileRecoveryOnlyWhenFullEmpty=true"
                            + " tileMerge=false"
                            + " workspaceCancellation=true"
                            + " enginePolicy=follow_main_setting");
            runNextFullPass();
        }

        private void runNextFullPass() {
            if (finished) return;
            if (isCancelled()) {
                cancel("before_full_pass");
                return;
            }
            if (passIndex >= SCALES.length + 1) {
                if (!fullCandidates.isEmpty()) {
                    finishBestFull("best_full_after_all_passes");
                } else if (!tiles.isEmpty()) {
                    startTiles();
                } else {
                    failEmpty("full_passes_empty");
                }
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

            if (isCancelled()) {
                recycleVariant(input);
                cancel("prepared_" + variant);
                return;
            }

            final float scaleX = input.getWidth() / (float) Math.max(1, source.getWidth());
            final float scaleY = input.getHeight() / (float) Math.max(1, source.getHeight());
            final long started = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "pass start variant=" + variant
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight()
                            + " scale=" + String.format(Locale.ROOT, "%.2fx%.2f", scaleX, scaleY));

            OcrEngine.recognizeDocument(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    try {
                        if (finished) return;
                        if (isCancelled()) {
                            cancel("after_" + variant);
                            return;
                        }
                        OcrDocument mapped = mapDocumentToSource(document,
                                new Rect(0, 0, source.getWidth(), source.getHeight()),
                                source.getWidth(), source.getHeight(), "full-" + variant + "-");
                        String key = normalize(mapped == null ? "" : mapped.fullText());
                        boolean usable = usable(mapped) && !key.isEmpty();
                        int vote = 0;
                        if (usable) {
                            Candidate candidate = new Candidate(mapped, scaleX, scaleY,
                                    variant, key, false);
                            fullCandidates.add(candidate);
                            vote = fullVotes.getOrDefault(key, 0) + 1;
                            fullVotes.put(key, vote);
                        }
                        DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                                "pass done variant=" + variant
                                        + " usable=" + usable
                                        + " vote=" + vote
                                        + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                        + " text=" + summarize(mapped == null ? "" : mapped.fullText())
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));

                        if (usable && vote >= 2) {
                            finishForKey(key, "full_consensus");
                        } else {
                            runNextFullPass();
                        }
                    } catch (Throwable t) {
                        lastError = t;
                        DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                                "pass map failed variant=" + variant + " error=" + safe(t));
                        runNextFullPass();
                    } finally {
                        recycleVariant(input);
                    }
                }

                @Override public void onFailure(Throwable error) {
                    recycleVariant(input);
                    if (finished) return;
                    lastError = error;
                    DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                            "pass failed variant=" + variant
                                    + " error=" + safe(error)
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    if (isCancelled()) cancel("failure_" + variant);
                    else runNextFullPass();
                }
            });
        }

        private void startTiles() {
            if (finished || tileStageStarted) return;
            if (isCancelled()) {
                cancel("before_tiles");
                return;
            }
            tileStageStarted = true;
            DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                    "start reason=all_full_passes_empty"
                            + " tiles=" + tiles.size()
                            + " overlapFraction=" + TILE_FRACTION
                            + " merge=false bestSingleTile=true"
                            + " enginePolicy=follow_main_setting");
            runNextTile();
        }

        private void runNextTile() {
            if (finished) return;
            if (isCancelled()) {
                cancel("before_tile");
                return;
            }
            if (tileIndex >= tiles.size()) {
                finishBestTile();
                return;
            }

            final TileSpec tile = tiles.get(tileIndex++);
            Bitmap preparedCrop = null;
            Bitmap preparedInput = null;
            float preparedScaleX = 1f;
            float preparedScaleY = 1f;
            try {
                preparedCrop = Bitmap.createBitmap(source, tile.bounds.left, tile.bounds.top,
                        tile.bounds.width(), tile.bounds.height());
                int minEdge = Math.max(1, Math.min(preparedCrop.getWidth(), preparedCrop.getHeight()));
                float requestedScale = Math.max(TILE_MIN_SCALE,
                        Math.min(TILE_MAX_SCALE, TILE_TARGET_MIN_EDGE_PX / (float) minEdge));
                preparedInput = makeVariant(preparedCrop, requestedScale, false);
                preparedScaleX = preparedInput.getWidth()
                        / (float) Math.max(1, preparedCrop.getWidth());
                preparedScaleY = preparedInput.getHeight()
                        / (float) Math.max(1, preparedCrop.getHeight());
            } catch (Throwable t) {
                lastError = t;
                if (preparedInput != null) recycleVariant(preparedInput);
                if (preparedCrop != null && preparedCrop != preparedInput) recycleVariant(preparedCrop);
                DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                        "prepare failed tile=" + tile.name
                                + " bounds=" + tile.bounds.toShortString()
                                + " error=" + safe(t));
                if (isCancelled()) cancel("prepare_" + tile.name);
                else runNextTile();
                return;
            }

            final Bitmap crop = preparedCrop;
            final Bitmap input = preparedInput;
            final float scaleX = preparedScaleX;
            final float scaleY = preparedScaleY;
            if (isCancelled()) {
                recycleVariant(input);
                if (crop != input) recycleVariant(crop);
                cancel("prepared_" + tile.name);
                return;
            }

            final long started = android.os.SystemClock.uptimeMillis();
            OcrEngine.recognizeDocument(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    try {
                        if (finished) return;
                        if (isCancelled()) {
                            cancel("after_" + tile.name);
                            return;
                        }
                        OcrDocument mapped = mapDocumentToSource(document, tile.bounds,
                                source.getWidth(), source.getHeight(), "tile-" + tile.name + "-");
                        String key = normalize(mapped == null ? "" : mapped.fullText());
                        if (usable(mapped) && !key.isEmpty()) {
                            tileCandidates.add(new Candidate(mapped, scaleX, scaleY,
                                    "tile-" + tile.name, key, true));
                        }
                        DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                                "pass done tile=" + tile.name
                                        + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                        + " text=" + summarize(mapped == null ? "" : mapped.fullText())
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));
                    } catch (Throwable t) {
                        lastError = t;
                    } finally {
                        recycleVariant(input);
                        if (crop != input) recycleVariant(crop);
                    }
                    if (!finished) runNextTile();
                }

                @Override public void onFailure(Throwable error) {
                    lastError = error;
                    recycleVariant(input);
                    if (crop != input) recycleVariant(crop);
                    DiagnosticLog.i(app, "G_CIRCLE_TILE_OCR",
                            "pass failed tile=" + tile.name
                                    + " error=" + safe(error));
                    if (finished) return;
                    if (isCancelled()) cancel("failure_" + tile.name);
                    else runNextTile();
                }
            });
        }

        private boolean isCancelled() {
            if (cancelled == null) return false;
            try { return cancelled.getAsBoolean(); }
            catch (Throwable ignored) { return true; }
        }

        private void cancel(String stage) {
            if (finished) return;
            finished = true;
            CancellationException error = new CancellationException(
                    "Circle local OCR cancelled at " + stage);
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "cancel stage=" + stage
                            + " fullPasses=" + fullCandidates.size()
                            + " tilePasses=" + tileCandidates.size());
            callback.onFailure(error);
        }

        private void finishForKey(String key, String reason) {
            if (finished) return;
            Candidate best = null;
            for (Candidate candidate : fullCandidates) {
                if (!candidate.key.equals(key)) continue;
                if (best == null || quality(candidate.document) > quality(best.document)) {
                    best = candidate;
                }
            }
            if (best == null) {
                finishBestFull(reason + "_missing_key");
                return;
            }
            finishCandidate(best, reason, fullVotes.getOrDefault(key, 0));
        }

        private void finishBestFull(String reason) {
            if (finished) return;
            if (isCancelled()) {
                cancel("finish_full");
                return;
            }
            Candidate best = null;
            int bestVotes = -1;
            double bestQuality = Double.NEGATIVE_INFINITY;
            for (Candidate candidate : fullCandidates) {
                int votes = fullVotes.getOrDefault(candidate.key, 0);
                double q = quality(candidate.document);
                if (votes > bestVotes || (votes == bestVotes && q > bestQuality)) {
                    best = candidate;
                    bestVotes = votes;
                    bestQuality = q;
                }
            }
            if (best != null) finishCandidate(best, reason, Math.max(1, bestVotes));
            else failEmpty(reason);
        }

        private void finishBestTile() {
            if (finished) return;
            if (isCancelled()) {
                cancel("finish_tile");
                return;
            }
            Candidate best = null;
            double bestQuality = Double.NEGATIVE_INFINITY;
            for (Candidate candidate : tileCandidates) {
                double q = quality(candidate.document);
                if (q > bestQuality) {
                    best = candidate;
                    bestQuality = q;
                }
            }
            if (best != null) finishCandidate(best, "best_single_tile_recovery", 1);
            else failEmpty("tile_recovery_empty");
        }

        private void finishCandidate(Candidate candidate, String reason, int votes) {
            if (finished || candidate == null) return;
            if (isCancelled()) {
                cancel("finish_candidate");
                return;
            }
            finished = true;
            DiagnosticLog.i(app, candidate.tile ? "G_CIRCLE_TILE_OCR" : "G_CIRCLE_MULTI_OCR",
                    "finish reason=" + reason
                            + " variant=" + candidate.variant
                            + " votes=" + votes
                            + " merge=false"
                            + " chars=" + candidate.document.chars().size()
                            + " text=" + summarize(candidate.document.fullText()));
            // Geometry is already normalized to the original local ROI.
            callback.onSuccess(candidate.document, 1f, 1f, candidate.variant);
        }

        private void failEmpty(String reason) {
            if (finished) return;
            if (isCancelled()) {
                cancel("fail_empty");
                return;
            }
            finished = true;
            Throwable error = lastError == null
                    ? new IllegalStateException("multi-scale OCR empty") : lastError;
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "finish reason=" + reason + " error=" + safe(error));
            callback.onFailure(error);
        }
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        recognize(context, bitmap, () -> false, callback);
    }

    static void recognize(Context context, Bitmap bitmap,
                          BooleanSupplier cancelled, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        new Session(context.getApplicationContext(), bitmap, cancelled, callback).start();
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

        Bitmap out = null;
        try {
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            float contrastValue = 1.8f;
            float offset = 128f * (1f - contrastValue);
            ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                    contrastValue, 0, 0, 0, offset,
                    0, contrastValue, 0, 0, offset,
                    0, 0, contrastValue, 0, offset,
                    0, 0, 0, 1, 0
            });
            matrix.postConcat(contrastMatrix);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            canvas.drawBitmap(scaled, 0f, 0f, paint);
            return out;
        } catch (Throwable t) {
            if (out != null && !out.isRecycled()) out.recycle();
            throw t;
        } finally {
            if (scaled != source && scaled != out && !scaled.isRecycled()) scaled.recycle();
        }
    }

    private static OcrDocument mapDocumentToSource(OcrDocument document, Rect target,
                                                   int width, int height, String prefix) {
        if (document == null || target == null || target.isEmpty()) return null;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, Math.max(1, document.imageWidth()),
                        Math.max(1, document.imageHeight())),
                new RectF(target));
        return mapper.mapDocument(document, false,
                Math.max(1, width), Math.max(1, height), prefix);
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isBitmapSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
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

    private static void recycleVariant(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private CircleMultiScaleOcr() {}
}
