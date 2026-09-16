package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds one comprehensive frozen-screen OCR index from a full-frame pass plus four overlapping
 * tiles. Every pass follows the main OCR engine setting through {@link OcrEngine}.
 *
 * <p>All tile results are mapped back to the original bitmap plane with {@link CoordinateMapper}.
 * Merge happens at whole-line granularity: competing OCR passes keep their own original character
 * geometry and the better complete line wins. Characters from different OCR passes are never
 * spliced together. The output therefore has one stable coordinate space while preserving the
 * engine's original line/word geometry for character-precise selection.</p>
 */
final class CirclePreindexTiledOcr {
    interface Callback {
        void onSuccess(OcrDocument document);
        void onFailure(Throwable error);
    }

    private static final float TILE_FRACTION = 0.64f;
    private static final int MIN_FRAME_EDGE_FOR_TILES = 640;

    private static final class Pass {
        final Rect roi;
        final String name;
        final boolean full;

        Pass(Rect roi, String name, boolean full) {
            this.roi = new Rect(roi);
            this.name = name;
            this.full = full;
        }
    }

    private static final class DocumentSource {
        final OcrDocument document;
        final String source;
        final boolean tile;
        final Rect roi;

        DocumentSource(OcrDocument document, String source, boolean tile, Rect roi) {
            this.document = document;
            this.source = source;
            this.tile = tile;
            this.roi = roi == null ? new Rect() : new Rect(roi);
        }
    }

    private static final class LineCandidate {
        final OcrDocument.Line line;
        final String source;
        final boolean tile;
        final Rect roi;

        LineCandidate(OcrDocument.Line line, String source, boolean tile, Rect roi) {
            this.line = line;
            this.source = source;
            this.tile = tile;
            this.roi = roi == null ? new Rect() : new Rect(roi);
        }
    }

    private static final class Session {
        final Context app;
        final Bitmap source;
        final Callback callback;
        final List<Pass> passes;
        final ArrayList<DocumentSource> documents = new ArrayList<>();
        int index;
        Throwable lastError;
        boolean finished;

        Session(Context app, Bitmap source, Callback callback) {
            this.app = app;
            this.source = source;
            this.callback = callback;
            this.passes = buildPasses(source.getWidth(), source.getHeight());
        }

        void start() {
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                    "start strategy=full_plus_overlap_tiles"
                            + " bitmap=" + source.getWidth() + "x" + source.getHeight()
                            + " passes=" + passes.size()
                            + " tileFraction=" + TILE_FRACTION
                            + " enginePolicy=follow_main_setting"
                            + " merge=line_level_spatial_choice"
                            + " preserveOriginalChars=true"
                            + " failOpen=full_frame");
            runNext();
        }

        private void runNext() {
            if (finished) return;
            if (index >= passes.size()) {
                finish();
                return;
            }

            final Pass pass = passes.get(index++);
            final Bitmap input;
            try {
                input = pass.full ? source : Bitmap.createBitmap(source,
                        pass.roi.left, pass.roi.top, pass.roi.width(), pass.roi.height());
            } catch (Throwable t) {
                lastError = t;
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                        "prepare failed source=" + pass.name
                                + " roi=" + pass.roi.toShortString()
                                + " error=" + safe(t));
                runNext();
                return;
            }

            final long started = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                    "pass start source=" + pass.name
                            + " roi=" + pass.roi.toShortString()
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight());

            OcrEngine.recognizeDocument(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    try {
                        OcrDocument mapped = pass.full
                                ? normalizeFull(document, source.getWidth(), source.getHeight())
                                : mapTile(document, pass.roi, source.getWidth(),
                                        source.getHeight(), pass.name);
                        if (usable(mapped)) {
                            documents.add(new DocumentSource(
                                    mapped, pass.name, !pass.full, pass.roi));
                        }
                        DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                                "pass done source=" + pass.name
                                        + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                        + " lines=" + (mapped == null ? 0 : mapped.lines().size())
                                        + " engine=" + (document == null ? "none" : document.engine())
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));
                    } catch (Throwable t) {
                        lastError = t;
                        DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                                "map failed source=" + pass.name + " error=" + safe(t));
                    } finally {
                        if (!pass.full) recycle(input);
                    }
                    runNext();
                }

                @Override public void onFailure(Throwable error) {
                    lastError = error;
                    DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                            "pass failed source=" + pass.name
                                    + " error=" + safe(error)
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    if (!pass.full) recycle(input);
                    runNext();
                }
            });
        }

        private void finish() {
            if (finished) return;
            finished = true;

            OcrDocument merged = null;
            Throwable mergeError = null;
            try {
                merged = merge(documents, source.getWidth(), source.getHeight());
            } catch (Throwable t) {
                mergeError = t;
                lastError = t;
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                        "merge failed error=" + safe(t) + " -> fail-open full-frame");
            }

            if (usable(merged)) {
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                        "finish usable=true sources=" + documents.size()
                                + " chars=" + merged.chars().size()
                                + " lines=" + merged.lines().size()
                                + " textChars=" + merged.fullText().length()
                                + " merge=line_level_spatial_choice"
                                + " fallback=false");
                callback.onSuccess(merged);
                return;
            }

            OcrDocument fallback = bestFallbackDocument(documents);
            if (usable(fallback)) {
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                        "finish usable=true sources=" + documents.size()
                                + " chars=" + fallback.chars().size()
                                + " lines=" + fallback.lines().size()
                                + " fallback=true reason="
                                + (mergeError == null ? "merged_empty" : safe(mergeError)));
                callback.onSuccess(fallback);
                return;
            }

            Throwable error = lastError == null
                    ? new IllegalStateException("tiled preindex OCR empty") : lastError;
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                    "finish usable=false sources=" + documents.size()
                            + " error=" + safe(error));
            callback.onFailure(error);
        }
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        new Session(context.getApplicationContext(), bitmap, callback).start();
    }

    private static List<Pass> buildPasses(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        ArrayList<Pass> out = new ArrayList<>();
        out.add(new Pass(new Rect(0, 0, w, h), "full", true));
        if (Math.min(w, h) < MIN_FRAME_EDGE_FOR_TILES) return List.copyOf(out);

        int tw = Math.max(1, Math.min(w, Math.round(w * TILE_FRACTION)));
        int th = Math.max(1, Math.min(h, Math.round(h * TILE_FRACTION)));
        if (tw >= w && th >= h) return List.copyOf(out);

        Set<String> seen = new HashSet<>();
        addPass(out, seen, new Rect(0, 0, tw, th), "tile-tl");
        addPass(out, seen, new Rect(w - tw, 0, w, th), "tile-tr");
        addPass(out, seen, new Rect(0, h - th, tw, h), "tile-bl");
        addPass(out, seen, new Rect(w - tw, h - th, w, h), "tile-br");
        return List.copyOf(out);
    }

    private static void addPass(List<Pass> out, Set<String> seen, Rect roi, String name) {
        if (roi == null || roi.isEmpty()) return;
        String key = roi.flattenToString();
        if (seen.add(key)) out.add(new Pass(roi, name, false));
    }

    private static OcrDocument normalizeFull(OcrDocument document, int width, int height) {
        if (document == null) return null;
        if (document.imageWidth() == width && document.imageHeight() == height) return document;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, document.imageWidth(), document.imageHeight()),
                new RectF(0f, 0f, Math.max(1, width), Math.max(1, height)));
        return mapper.mapDocument(document, false,
                Math.max(1, width), Math.max(1, height), "full-");
    }

    private static OcrDocument mapTile(OcrDocument document, Rect roi,
                                       int width, int height, String name) {
        if (document == null || roi == null || roi.isEmpty()) return null;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, document.imageWidth(), document.imageHeight()),
                new RectF(roi));
        return mapper.mapDocument(document, false,
                Math.max(1, width), Math.max(1, height), name + "-");
    }

    private static OcrDocument bestFallbackDocument(List<DocumentSource> sources) {
        if (sources == null || sources.isEmpty()) return null;
        OcrDocument firstUsable = null;
        for (DocumentSource source : sources) {
            if (source == null || !usable(source.document)) continue;
            if (firstUsable == null) firstUsable = source.document;
            if (!source.tile || "full".equals(source.source)) return source.document;
        }
        return firstUsable;
    }

    private static OcrDocument merge(List<DocumentSource> sources, int width, int height) {
        if (sources == null || sources.isEmpty()) return null;

        ArrayList<LineCandidate> accepted = new ArrayList<>();
        for (DocumentSource source : sources) {
            if (source == null || !usable(source.document)) continue;
            for (OcrDocument.Line line : source.document.lines()) {
                if (!usableLine(line)) continue;
                LineCandidate incoming = new LineCandidate(
                        line, source.source, source.tile, source.roi);
                int duplicate = findDuplicateLine(accepted, incoming);
                if (duplicate < 0) {
                    accepted.add(incoming);
                } else if (preferLine(incoming, accepted.get(duplicate), width, height)) {
                    accepted.set(duplicate, incoming);
                }
            }
        }
        if (accepted.isEmpty()) return null;

        accepted.sort((a, b) -> compareVisual(a.line.bounds(), b.line.bounds()));

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int globalGroup = 0;
        int globalOrder = 0;
        float confidenceSum = 0f;
        int confidenceCount = 0;

        for (LineCandidate candidate : accepted) {
            OcrDocument.Line sourceLine = candidate.line;
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            HashMap<Integer, Integer> groupMap = new HashMap<>();

            for (OcrDocument.CharUnit unit : sourceLine.chars()) {
                if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
                Integer mappedGroup = groupMap.get(unit.group());
                if (mappedGroup == null) {
                    mappedGroup = globalGroup++;
                    groupMap.put(unit.group(), mappedGroup);
                }
                chars.add(new OcrDocument.CharUnit(
                        unit.text(), unit.bounds(), unit.confidence(),
                        lineId, mappedGroup, globalOrder++));
                confidenceSum += unit.confidence();
                confidenceCount++;
            }

            if (chars.isEmpty()) continue;
            String text = sourceLine.text() == null ? "" : sourceLine.text().trim();
            if (text.isEmpty()) {
                StringBuilder rebuilt = new StringBuilder();
                for (OcrDocument.CharUnit unit : chars) rebuilt.append(unit.text());
                text = rebuilt.toString();
            }
            if (text.isEmpty()) continue;

            Rect bounds = sourceLine.bounds();
            if (bounds.isEmpty()) {
                bounds = new Rect(chars.get(0).bounds());
                for (int i = 1; i < chars.size(); i++) bounds.union(chars.get(i).bounds());
            }

            lines.add(new OcrDocument.Line(text, bounds, sourceLine.confidence(), chars));
            blocks.add(text);
            if (full.length() > 0) full.append('\n');
            full.append(text);
            lineId++;
        }
        if (lines.isEmpty()) return null;

        float confidence = confidenceCount <= 0 ? 0f : confidenceSum / confidenceCount;
        double score = lines.size() * 5d + globalOrder + confidence * 100d;
        return new OcrDocument(full.toString(), blocks, lines,
                "preindex-line-choice-full+overlap-tiles", confidence, score,
                Math.max(1, width), Math.max(1, height));
    }

    private static int findDuplicateLine(List<LineCandidate> accepted, LineCandidate incoming) {
        String incomingText = normalizeText(incoming.line.text());
        if (incomingText.isEmpty()) return -1;
        Rect incomingRect = incoming.line.bounds();

        for (int i = 0; i < accepted.size(); i++) {
            LineCandidate existing = accepted.get(i);
            String existingText = normalizeText(existing.line.text());
            if (existingText.isEmpty()) continue;

            Rect existingRect = existing.line.bounds();
            float overlap = overlapRatio(incomingRect, existingRect);
            if (overlap <= 0f) continue;

            if (incomingText.equals(existingText) && overlap >= 0.30f) return i;

            boolean contains = incomingText.contains(existingText)
                    || existingText.contains(incomingText);
            float vertical = axisOverlapRatio(
                    incomingRect.top, incomingRect.bottom,
                    existingRect.top, existingRect.bottom);
            if (contains && overlap >= 0.40f && vertical >= 0.60f) return i;

            float similarity = textSimilarity(incomingText, existingText);
            if (similarity >= 0.78f && overlap >= 0.50f && vertical >= 0.60f) return i;
        }
        return -1;
    }

    private static boolean preferLine(LineCandidate incoming, LineCandidate existing,
                                      int frameWidth, int frameHeight) {
        String aText = normalizeText(incoming.line.text());
        String bText = normalizeText(existing.line.text());
        boolean aContainsB = !bText.isEmpty() && aText.contains(bText);
        boolean bContainsA = !aText.isEmpty() && bText.contains(aText);
        boolean aClipped = touchesInternalTileEdge(incoming, frameWidth, frameHeight);
        boolean bClipped = touchesInternalTileEdge(existing, frameWidth, frameHeight);

        if (aContainsB != bContainsA) {
            if (aContainsB && !aClipped
                    && incoming.line.confidence() + 0.15f >= existing.line.confidence()) {
                return true;
            }
            if (bContainsA && !bClipped
                    && existing.line.confidence() + 0.15f >= incoming.line.confidence()) {
                return false;
            }
        }

        if (aClipped != bClipped) return !aClipped;

        float a = lineQuality(incoming, frameWidth, frameHeight);
        float b = lineQuality(existing, frameWidth, frameHeight);
        if (Math.abs(a - b) > 0.001f) return a > b;

        int aChars = incoming.line.chars().size();
        int bChars = existing.line.chars().size();
        if (aChars != bChars) return aChars > bChars;

        if (incoming.tile != existing.tile) return incoming.tile;

        long areaA = area(incoming.line.bounds());
        long areaB = area(existing.line.bounds());
        return areaA > 0 && (areaB <= 0 || areaA < areaB);
    }

    private static float lineQuality(LineCandidate candidate, int frameWidth, int frameHeight) {
        float confidence = candidate.line.confidence();
        if (!Float.isFinite(confidence)) confidence = 0f;
        confidence = Math.max(0f, Math.min(1f, confidence));

        String text = normalizeText(candidate.line.text());
        int charCount = candidate.line.chars().size();
        float score = confidence;
        score += Math.min(24, charCount) * 0.004f;
        score += Math.min(40, text.codePointCount(0, text.length())) * 0.0015f;
        if (candidate.tile) score += 0.03f;
        if (touchesInternalTileEdge(candidate, frameWidth, frameHeight)) score -= 0.18f;
        return score;
    }

    private static boolean touchesInternalTileEdge(LineCandidate candidate,
                                                   int frameWidth, int frameHeight) {
        if (candidate == null || !candidate.tile || candidate.roi.isEmpty()) return false;
        Rect line = candidate.line.bounds();
        if (line.isEmpty()) return false;

        int margin = Math.max(3, Math.min(12, Math.max(1, line.height()) / 3));
        Rect roi = candidate.roi;
        boolean left = roi.left > 0 && line.left <= roi.left + margin;
        boolean top = roi.top > 0 && line.top <= roi.top + margin;
        boolean right = roi.right < frameWidth && line.right >= roi.right - margin;
        boolean bottom = roi.bottom < frameHeight && line.bottom >= roi.bottom - margin;
        return left || top || right || bottom;
    }

    private static int compareVisual(Rect a, Rect b) {
        int tolerance = Math.max(3,
                Math.min(Math.max(1, a.height()), Math.max(1, b.height())) / 2);
        int dy = a.centerY() - b.centerY();
        if (Math.abs(dy) > tolerance) return Integer.compare(a.centerY(), b.centerY());
        return Integer.compare(a.left, b.left);
    }

    private static float overlapRatio(Rect a, Rect b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        Rect overlap = new Rect();
        if (!overlap.setIntersect(a, b)) return 0f;
        long overlapArea = area(overlap);
        long smaller = Math.max(1L, Math.min(area(a), area(b)));
        return overlapArea / (float) smaller;
    }

    private static float axisOverlapRatio(int aStart, int aEnd, int bStart, int bEnd) {
        int overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
        int smaller = Math.max(1, Math.min(
                Math.max(1, aEnd - aStart),
                Math.max(1, bEnd - bStart)));
        return overlap / (float) smaller;
    }

    private static long area(Rect r) {
        return r == null || r.isEmpty() ? 0L : Math.max(1L, (long) r.width() * r.height());
    }

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (!Character.isWhitespace(cp)) out.appendCodePoint(cp);
        });
        return out.toString();
    }

    private static float textSimilarity(String a, String b) {
        if (a == null || b == null) return 0f;
        if (a.equals(b)) return 1f;
        int[] ac = a.codePoints().toArray();
        int[] bc = b.codePoints().toArray();
        int max = Math.max(ac.length, bc.length);
        if (max == 0) return 1f;
        int distance = levenshtein(ac, bc);
        return Math.max(0f, 1f - distance / (float) max);
    }

    private static int levenshtein(int[] a, int[] b) {
        if (a.length == 0) return b.length;
        if (b.length == 0) return a.length;

        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) previous[j] = j;

        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }

    private static boolean usableLine(OcrDocument.Line line) {
        return line != null && !line.text().isBlank()
                && !line.bounds().isEmpty() && !line.chars().isEmpty();
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isBitmapSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CirclePreindexTiledOcr() {}
}
