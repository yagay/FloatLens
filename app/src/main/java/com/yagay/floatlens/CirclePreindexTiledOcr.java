package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds one comprehensive frozen-screen OCR index from a full-frame pass plus four overlapping
 * tiles. Every pass follows the main OCR engine setting through {@link OcrEngine}.
 *
 * <p>All tile results are mapped back to the original bitmap plane with {@link CoordinateMapper}
 * before character-level spatial de-duplication. The output therefore has one stable coordinate
 * space regardless of screen resolution, density, rotation, capture scaling or OCR engine.</p>
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

        DocumentSource(OcrDocument document, String source, boolean tile) {
            this.document = document;
            this.source = source;
            this.tile = tile;
        }
    }

    private static final class CharCandidate {
        final OcrDocument.CharUnit unit;
        final String source;
        final boolean tile;

        CharCandidate(OcrDocument.CharUnit unit, String source, boolean tile) {
            this.unit = unit;
            this.source = source;
            this.tile = tile;
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
                            + " merge=character_spatial_dedup");
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
                if (pass.full) {
                    input = source;
                } else {
                    input = Bitmap.createBitmap(source, pass.roi.left, pass.roi.top,
                            pass.roi.width(), pass.roi.height());
                }
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
                                : mapTile(document, pass.roi, source.getWidth(), source.getHeight(), pass.name);
                        if (usable(mapped)) {
                            documents.add(new DocumentSource(mapped, pass.name, !pass.full));
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
            OcrDocument merged = merge(documents, source.getWidth(), source.getHeight());
            if (usable(merged)) {
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX_TILE",
                        "finish usable=true sources=" + documents.size()
                                + " chars=" + merged.chars().size()
                                + " lines=" + merged.lines().size()
                                + " textChars=" + merged.fullText().length());
                callback.onSuccess(merged);
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
        return mapper.mapDocument(document, false, Math.max(1, width), Math.max(1, height),
                "full-");
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

    private static OcrDocument merge(List<DocumentSource> sources, int width, int height) {
        if (sources == null || sources.isEmpty()) return null;

        ArrayList<CharCandidate> accepted = new ArrayList<>();
        for (DocumentSource source : sources) {
            if (source == null || !usable(source.document)) continue;
            for (OcrDocument.CharUnit unit : source.document.chars()) {
                if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
                CharCandidate incoming = new CharCandidate(unit, source.source, source.tile);
                int duplicate = findDuplicate(accepted, incoming);
                if (duplicate < 0) {
                    accepted.add(incoming);
                } else if (prefer(incoming, accepted.get(duplicate))) {
                    accepted.set(duplicate, incoming);
                }
            }
        }
        if (accepted.isEmpty()) return null;

        accepted.sort((a, b) -> compareVisual(a.unit.bounds(), b.unit.bounds()));
        ArrayList<ArrayList<CharCandidate>> rows = new ArrayList<>();
        ArrayList<Float> rowCenters = new ArrayList<>();
        ArrayList<Float> rowHeights = new ArrayList<>();

        for (CharCandidate candidate : accepted) {
            Rect r = candidate.unit.bounds();
            int best = -1;
            float bestDistance = Float.MAX_VALUE;
            for (int i = 0; i < rows.size(); i++) {
                float tolerance = Math.max(4f, Math.min(rowHeights.get(i), r.height()) * 0.62f);
                float distance = Math.abs(r.centerY() - rowCenters.get(i));
                if (distance <= tolerance && distance < bestDistance) {
                    best = i;
                    bestDistance = distance;
                }
            }
            if (best < 0) {
                ArrayList<CharCandidate> row = new ArrayList<>();
                row.add(candidate);
                rows.add(row);
                rowCenters.add((float) r.centerY());
                rowHeights.add((float) Math.max(1, r.height()));
            } else {
                ArrayList<CharCandidate> row = rows.get(best);
                row.add(candidate);
                int count = row.size();
                rowCenters.set(best, (rowCenters.get(best) * (count - 1) + r.centerY()) / count);
                rowHeights.set(best, (rowHeights.get(best) * (count - 1) + Math.max(1, r.height())) / count);
            }
        }

        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(rowCenters::get));

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int group = 0;
        int globalOrder = 0;
        float confidenceSum = 0f;
        int confidenceCount = 0;

        for (int rowIndex : order) {
            ArrayList<CharCandidate> row = rows.get(rowIndex);
            row.sort(Comparator.comparingInt(c -> c.unit.bounds().left));
            if (row.isEmpty()) continue;

            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            StringBuilder lineText = new StringBuilder();
            Rect union = null;
            Rect previous = null;
            String previousText = "";
            int currentGroup = group++;

            for (CharCandidate candidate : row) {
                OcrDocument.CharUnit unit = candidate.unit;
                Rect r = unit.bounds();
                if (previous != null) {
                    int gap = r.left - previous.right;
                    float refHeight = Math.max(1f,
                            Math.min(Math.max(1, previous.height()), Math.max(1, r.height())));
                    boolean groupBreak = gap > Math.max(3f, refHeight * 0.42f);
                    if (groupBreak) currentGroup = group++;
                    if (shouldInsertSpace(previousText, unit.text(), groupBreak)) lineText.append(' ');
                }
                lineText.append(unit.text());
                chars.add(new OcrDocument.CharUnit(unit.text(), r, unit.confidence(),
                        lineId, currentGroup, globalOrder++));
                if (union == null) union = new Rect(r); else union.union(r);
                confidenceSum += unit.confidence();
                confidenceCount++;
                previous = r;
                previousText = unit.text();
            }

            String text = lineText.toString().trim();
            if (chars.isEmpty() || text.isEmpty() || union == null || union.isEmpty()) continue;
            float lineConfidence = 0f;
            for (OcrDocument.CharUnit c : chars) lineConfidence += c.confidence();
            lineConfidence /= chars.size();
            lines.add(new OcrDocument.Line(text, union, lineConfidence, chars));
            blocks.add(text);
            if (full.length() > 0) full.append('\n');
            full.append(text);
            lineId++;
        }
        if (lines.isEmpty()) return null;

        float confidence = confidenceCount <= 0 ? 0f : confidenceSum / confidenceCount;
        double score = lines.size() * 5d + globalOrder + confidence * 100d;
        return new OcrDocument(full.toString(), blocks, lines,
                "preindex-full+overlap-tiles", confidence, score,
                Math.max(1, width), Math.max(1, height));
    }

    private static int findDuplicate(List<CharCandidate> accepted, CharCandidate incoming) {
        String incomingText = normalizeGlyph(incoming.unit.text());
        if (incomingText.isEmpty()) return -1;
        Rect incomingRect = incoming.unit.bounds();
        for (int i = 0; i < accepted.size(); i++) {
            CharCandidate existing = accepted.get(i);
            if (!incomingText.equals(normalizeGlyph(existing.unit.text()))) continue;
            if (overlapRatio(incomingRect, existing.unit.bounds()) >= 0.45f) return i;
        }
        return -1;
    }

    private static boolean prefer(CharCandidate incoming, CharCandidate existing) {
        float a = incoming.unit.confidence() + (incoming.tile ? 0.035f : 0f);
        float b = existing.unit.confidence() + (existing.tile ? 0.035f : 0f);
        if (Math.abs(a - b) > 0.001f) return a > b;
        long areaA = area(incoming.unit.bounds());
        long areaB = area(existing.unit.bounds());
        return areaA > 0 && areaA < areaB;
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

    private static long area(Rect r) {
        return r == null || r.isEmpty() ? 0L : Math.max(1L, (long) r.width() * r.height());
    }

    private static String normalizeGlyph(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (!Character.isWhitespace(cp)) out.appendCodePoint(cp);
        });
        return out.toString();
    }

    private static boolean shouldInsertSpace(String previous, String current, boolean groupBreak) {
        if (!groupBreak || previous == null || previous.isEmpty() || current == null || current.isEmpty()) {
            return false;
        }
        int a = previous.codePointBefore(previous.length());
        int b = current.codePointAt(0);
        return !isCjk(a) && !isCjk(b) && !isPunctuation(a) && !isPunctuation(b);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static boolean isPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
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
