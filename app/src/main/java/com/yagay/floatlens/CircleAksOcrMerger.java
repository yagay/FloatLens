package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AKS-style global merge for Circle OCR passes.
 *
 * <p>ML Kit output is first adapted into AKS-style visual words. Latin/digit Elements remain
 * independent words. Adjacent CJK groups on the same recognizer line are joined when their
 * geometry shows one continuous visual run. Every original ML Kit character rectangle is kept.
 * Full/TL/TR/BL/BR candidates are then clustered only when they represent the same physical word.
 * An exact whole-word consensus across independent passes wins; when every pass disagrees, the
 * candidate that is safest from its crop boundary wins. Characters are never fused across OCR
 * sources.</p>
 */
final class CircleAksOcrMerger {
    private static final float SAME_ROW_CENTER_FACTOR = 0.60f;

    // ML Kit may split a continuous Chinese visual word into one Element per character. Join only
    // when both neighboring groups look CJK-like and geometry strongly indicates continuity.
    private static final float CJK_MIN_HEIGHT_RATIO = 0.62f;
    private static final float CJK_MAX_CENTER_DELTA_FACTOR = 0.42f;
    private static final float CJK_MAX_GAP_HEIGHT_FACTOR = 0.58f;

    // Spatial conflict clustering is deliberately stricter than ordinary line grouping. A smaller
    // contained fragment may join a larger candidate, but unrelated neighboring words must not.
    private static final float WORD_MIN_HEIGHT_RATIO = 0.60f;
    private static final float WORD_MAX_CENTER_Y_FACTOR = 0.45f;
    private static final float WORD_MUTUAL_OVERLAP = 0.62f;
    private static final float WORD_CONTAINED_OVERLAP = 0.82f;
    private static final float WORD_MIN_EXTENT_RATIO = 0.55f;

    private static final class RawGroup {
        final String text;
        final Rect bounds;
        final List<OcrDocument.CharUnit> chars;
        final float confidence;

        RawGroup(String text, Rect bounds, List<OcrDocument.CharUnit> chars, float confidence) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.chars = chars == null ? List.of() : List.copyOf(chars);
            this.confidence = confidence;
        }
    }

    private static final class WordCandidate {
        final String source;
        final boolean fullFrame;
        final Rect coverage;
        final String text;
        final Rect bounds;
        final List<OcrDocument.CharUnit> chars;
        final float confidence;
        final int priority;

        WordCandidate(String source, boolean fullFrame, Rect coverage,
                      String text, Rect bounds, List<OcrDocument.CharUnit> chars,
                      float confidence, int priority) {
            this.source = source == null ? "unknown" : source;
            this.fullFrame = fullFrame;
            this.coverage = coverage == null ? new Rect() : new Rect(coverage);
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.chars = chars == null ? List.of() : List.copyOf(chars);
            this.confidence = confidence;
            this.priority = priority;
        }
    }

    static OcrDocument merge(List<CircleOcrIndex.Entry> entries) {
        if (entries == null || entries.isEmpty()) return null;

        ArrayList<WordCandidate> all = new ArrayList<>();
        OcrDocument template = null;
        for (int sourceIndex = 0; sourceIndex < entries.size(); sourceIndex++) {
            CircleOcrIndex.Entry entry = entries.get(sourceIndex);
            if (entry == null || entry.document == null || entry.document.chars().isEmpty()) continue;
            if (template == null) template = entry.document;
            collectWords(all, entry, sourceIndex);
        }
        if (template == null || all.isEmpty()) return null;

        // AKS supplies the five spatial observations. Unlike the original exact-text-only de-dupe,
        // resolve candidates for the same physical word before rebuilding the global document.
        ArrayList<WordCandidate> accepted = resolveSpatialWords(all);
        if (accepted.isEmpty()) return null;

        accepted.sort(Comparator
                .comparingInt((WordCandidate w) -> w.bounds.top)
                .thenComparingInt(w -> w.bounds.left));

        ArrayList<ArrayList<WordCandidate>> rows = new ArrayList<>();
        ArrayList<WordCandidate> current = null;
        WordCandidate previous = null;
        for (WordCandidate candidate : accepted) {
            boolean sameRow = previous != null
                    && Math.abs(candidate.bounds.centerY() - previous.bounds.centerY())
                    < Math.max(1f, previous.bounds.height() * SAME_ROW_CENTER_FACTOR);
            if (current == null || !sameRow) {
                current = new ArrayList<>();
                rows.add(current);
            }
            current.add(candidate);
            previous = candidate;
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int groupId = 0;
        int order = 0;
        float confidenceSum = 0f;
        int confidenceCount = 0;

        for (ArrayList<WordCandidate> row : rows) {
            if (row.isEmpty()) continue;
            row.sort(Comparator.comparingInt(w -> w.bounds.left));
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect lineBounds = null;
            StringBuilder lineText = new StringBuilder();
            WordCandidate previousWord = null;
            float lineConfidenceSum = 0f;
            int lineConfidenceCount = 0;

            for (WordCandidate word : row) {
                if (word.text.isBlank() || word.bounds.isEmpty() || word.chars.isEmpty()) continue;
                if (previousWord != null && shouldInsertSpace(previousWord.text, word.text)) {
                    lineText.append(' ');
                }
                lineText.append(word.text);

                int outputGroup = groupId++;
                for (OcrDocument.CharUnit source : word.chars) {
                    if (source == null || source.text().isBlank() || source.bounds().isEmpty()) continue;
                    Rect bounds = source.bounds();
                    chars.add(new OcrDocument.CharUnit(source.text(), bounds, source.confidence(),
                            lineId, outputGroup, order++));
                    if (lineBounds == null) lineBounds = new Rect(bounds); else lineBounds.union(bounds);
                    confidenceSum += source.confidence();
                    confidenceCount++;
                    lineConfidenceSum += source.confidence();
                    lineConfidenceCount++;
                }
                previousWord = word;
            }

            String text = lineText.toString().trim();
            if (chars.isEmpty() || lineBounds == null || lineBounds.isEmpty() || text.isEmpty()) continue;
            float lineConfidence = lineConfidenceCount == 0 ? 0f
                    : lineConfidenceSum / lineConfidenceCount;
            lines.add(new OcrDocument.Line(text, lineBounds, lineConfidence, chars));
            blocks.add(text);
            if (full.length() > 0) full.append('\n');
            full.append(text);
            lineId++;
        }

        if (lines.isEmpty()) return null;
        float confidence = confidenceCount == 0 ? 0f : confidenceSum / confidenceCount;
        double score = lines.size() * 5d;
        for (OcrDocument.Line line : lines) score += line.chars().size();
        String engine = "aks-merged-mlkit-word-consensus";
        if (template.isScreenSpace()) {
            return OcrDocument.screenSpace(full.toString(), blocks, lines, engine,
                    confidence, score, template.imageWidth(), template.imageHeight());
        }
        return new OcrDocument(full.toString(), blocks, lines, engine,
                confidence, score, template.imageWidth(), template.imageHeight());
    }

    private static ArrayList<WordCandidate> resolveSpatialWords(List<WordCandidate> input) {
        ArrayList<WordCandidate> ordered = new ArrayList<>();
        for (WordCandidate candidate : input) {
            if (candidate == null || candidate.text.isBlank() || candidate.bounds.isEmpty()) continue;
            ordered.add(candidate);
        }
        ordered.sort(Comparator
                .comparingInt((WordCandidate w) -> w.bounds.top)
                .thenComparingInt(w -> w.bounds.left)
                .thenComparingInt(w -> w.priority));

        ArrayList<ArrayList<WordCandidate>> clusters = new ArrayList<>();
        for (WordCandidate candidate : ordered) {
            ArrayList<WordCandidate> target = null;
            for (ArrayList<WordCandidate> cluster : clusters) {
                boolean matches = false;
                for (WordCandidate member : cluster) {
                    if (samePhysicalWord(candidate, member)) {
                        matches = true;
                        break;
                    }
                }
                if (matches) {
                    target = cluster;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                clusters.add(target);
            }
            target.add(candidate);
        }

        ArrayList<WordCandidate> out = new ArrayList<>(clusters.size());
        for (ArrayList<WordCandidate> cluster : clusters) {
            WordCandidate winner = chooseWholeWord(cluster);
            if (winner != null) out.add(winner);
        }
        return out;
    }

    private static WordCandidate chooseWholeWord(List<WordCandidate> cluster) {
        if (cluster == null || cluster.isEmpty()) return null;
        if (cluster.size() == 1) return cluster.get(0);

        Map<String, ArrayList<WordCandidate>> byText = new LinkedHashMap<>();
        for (WordCandidate candidate : cluster) {
            String key = normalizeText(candidate.text);
            if (key.isEmpty()) continue;
            byText.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        if (byText.isEmpty()) return null;

        WordCandidate overall = null;
        int bestSupport = -1;
        float bestQuality = -Float.MAX_VALUE;
        for (ArrayList<WordCandidate> family : byText.values()) {
            Set<String> sources = new HashSet<>();
            WordCandidate familyBest = null;
            float familyQuality = -Float.MAX_VALUE;
            for (WordCandidate candidate : family) {
                sources.add(candidate.source);
                float quality = candidateQuality(candidate);
                if (familyBest == null || quality > familyQuality) {
                    familyBest = candidate;
                    familyQuality = quality;
                }
            }
            int support = sources.size();
            if (overall == null || support > bestSupport
                    || (support == bestSupport && familyQuality > bestQuality)) {
                overall = familyBest;
                bestSupport = support;
                bestQuality = familyQuality;
            }
        }
        return overall;
    }

    private static float candidateQuality(WordCandidate candidate) {
        if (candidate == null || candidate.bounds.isEmpty()) return -Float.MAX_VALUE;
        float h = Math.max(1f, candidate.bounds.height());
        float margin = cropMargin(candidate);
        float normalizedMargin = margin / h;
        float safeMargin = clamp(normalizedMargin, -1f, 4f);

        // Regional crops are useful when the word is comfortably inside them, but a crop-edge word
        // is less trustworthy because OCR can see only part of the visual word. Full frame remains a
        // stable fallback rather than automatically losing to BR/BL/TR/TL ordering.
        float quality = safeMargin * 2f;
        if (candidate.fullFrame) {
            quality += 0.25f;
        } else if (normalizedMargin >= 1f) {
            quality += 1.50f;
        }

        float confidence = candidate.confidence;
        if (confidence > 1f) confidence /= 100f;
        quality += clamp(confidence, 0f, 1f) * 0.5f;
        quality += Math.min(20, candidate.chars.size()) * 0.02f;
        quality += candidate.priority * 0.001f; // deterministic final tie-break only.
        return quality;
    }

    private static float cropMargin(WordCandidate candidate) {
        if (candidate == null || candidate.coverage.isEmpty() || candidate.bounds.isEmpty()) return 0f;
        float left = candidate.bounds.left - candidate.coverage.left;
        float top = candidate.bounds.top - candidate.coverage.top;
        float right = candidate.coverage.right - candidate.bounds.right;
        float bottom = candidate.coverage.bottom - candidate.bounds.bottom;
        return Math.min(Math.min(left, right), Math.min(top, bottom));
    }

    private static boolean samePhysicalWord(WordCandidate a, WordCandidate b) {
        if (a == null || b == null || a.bounds.isEmpty() || b.bounds.isEmpty()) return false;
        if (a.source.equals(b.source)) return false;

        float ah = Math.max(1f, a.bounds.height());
        float bh = Math.max(1f, b.bounds.height());
        float heightRatio = Math.min(ah, bh) / Math.max(ah, bh);
        if (heightRatio < WORD_MIN_HEIGHT_RATIO) return false;
        if (Math.abs(a.bounds.exactCenterY() - b.bounds.exactCenterY())
                > Math.max(ah, bh) * WORD_MAX_CENTER_Y_FACTOR) return false;

        Rect intersection = new Rect();
        if (!intersection.setIntersect(a.bounds, b.bounds)) return false;
        long overlap = Math.max(0L, (long) intersection.width() * intersection.height());
        long areaA = Math.max(1L, (long) a.bounds.width() * a.bounds.height());
        long areaB = Math.max(1L, (long) b.bounds.width() * b.bounds.height());
        float ratioA = overlap / (float) areaA;
        float ratioB = overlap / (float) areaB;
        if (ratioA >= WORD_MUTUAL_OVERLAP && ratioB >= WORD_MUTUAL_OVERLAP) return true;

        long minArea = Math.min(areaA, areaB);
        long maxArea = Math.max(areaA, areaB);
        float contained = overlap / (float) Math.max(1L, minArea);
        float extentRatio = minArea / (float) Math.max(1L, maxArea);
        return contained >= WORD_CONTAINED_OVERLAP && extentRatio >= WORD_MIN_EXTENT_RATIO;
    }

    private static void collectWords(List<WordCandidate> out, CircleOcrIndex.Entry entry,
                                     int priority) {
        for (OcrDocument.Line line : entry.document.lines()) {
            if (line == null || line.chars().isEmpty()) continue;

            Map<Integer, ArrayList<OcrDocument.CharUnit>> grouped = new LinkedHashMap<>();
            for (OcrDocument.CharUnit unit : line.chars()) {
                if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
                grouped.computeIfAbsent(unit.group(), ignored -> new ArrayList<>()).add(unit);
            }

            ArrayList<RawGroup> raw = new ArrayList<>();
            for (ArrayList<OcrDocument.CharUnit> chars : grouped.values()) {
                RawGroup group = rawGroup(chars);
                if (group != null) raw.add(group);
            }
            if (raw.isEmpty()) continue;
            raw.sort(Comparator.comparingInt(g -> g.bounds.left));

            RawGroup pending = raw.get(0);
            for (int i = 1; i < raw.size(); i++) {
                RawGroup next = raw.get(i);
                if (shouldJoinCjkGroups(pending, next)) {
                    pending = joinGroups(pending, next);
                } else {
                    addWord(out, entry, pending, priority);
                    pending = next;
                }
            }
            addWord(out, entry, pending, priority);
        }
    }

    private static RawGroup rawGroup(List<OcrDocument.CharUnit> input) {
        if (input == null || input.isEmpty()) return null;
        ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
        for (OcrDocument.CharUnit unit : input) {
            if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
            chars.add(unit);
        }
        if (chars.isEmpty()) return null;
        chars.sort(Comparator.comparingInt(c -> c.bounds().left));

        StringBuilder text = new StringBuilder();
        Rect bounds = null;
        float confidence = 0f;
        for (OcrDocument.CharUnit unit : chars) {
            text.append(unit.text());
            Rect r = unit.bounds();
            if (bounds == null) bounds = new Rect(r); else bounds.union(r);
            confidence += unit.confidence();
        }
        if (bounds == null || bounds.isEmpty() || text.length() == 0) return null;
        return new RawGroup(text.toString(), bounds, chars, confidence / chars.size());
    }

    private static void addWord(List<WordCandidate> out, CircleOcrIndex.Entry entry,
                                RawGroup group, int priority) {
        if (entry == null || group == null || group.text.isBlank()
                || group.bounds.isEmpty() || group.chars.isEmpty()) return;
        out.add(new WordCandidate(entry.source, entry.fullFrame, entry.coverage,
                group.text, group.bounds, group.chars, group.confidence, priority));
    }

    private static RawGroup joinGroups(RawGroup left, RawGroup right) {
        ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>(
                left.chars.size() + right.chars.size());
        chars.addAll(left.chars);
        chars.addAll(right.chars);
        chars.sort(Comparator.comparingInt(c -> c.bounds().left));
        Rect bounds = new Rect(left.bounds);
        bounds.union(right.bounds);
        float confidence = (left.confidence * left.chars.size()
                + right.confidence * right.chars.size())
                / Math.max(1, left.chars.size() + right.chars.size());
        return new RawGroup(left.text + right.text, bounds, chars, confidence);
    }

    private static boolean shouldJoinCjkGroups(RawGroup left, RawGroup right) {
        if (left == null || right == null || left.bounds.isEmpty() || right.bounds.isEmpty()) return false;
        if (!isCjkVisualText(left.text) || !isCjkVisualText(right.text)) return false;

        float lh = Math.max(1f, left.bounds.height());
        float rh = Math.max(1f, right.bounds.height());
        float heightRatio = Math.min(lh, rh) / Math.max(lh, rh);
        if (heightRatio < CJK_MIN_HEIGHT_RATIO) return false;

        float centerDelta = Math.abs(left.bounds.exactCenterY() - right.bounds.exactCenterY());
        if (centerDelta > Math.max(lh, rh) * CJK_MAX_CENTER_DELTA_FACTOR) return false;

        float gap = Math.max(0f, right.bounds.left - left.bounds.right);
        float maxGap = Math.max(lh, rh) * CJK_MAX_GAP_HEIGHT_FACTOR;
        return gap <= maxGap;
    }

    private static String normalizeText(String text) {
        if (text == null) return "";
        return text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * True for a CJK run that may include common CJK punctuation. Latin letters and digits prevent
     * joining so mixed-language labels keep ML Kit's own Element boundaries.
     */
    private static boolean isCjkVisualText(String text) {
        if (text == null || text.isBlank()) return false;
        boolean sawCjk = false;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            if (isCjk(cp)) {
                sawCjk = true;
                continue;
            }
            if (isCjkPunctuation(cp)) continue;
            return false;
        }
        return sawCjk;
    }

    private static boolean isCjkPunctuation(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)
                || cp == 0xFF0C || cp == 0x3002 || cp == 0xFF01 || cp == 0xFF1F
                || cp == 0xFF1A || cp == 0xFF1B || cp == 0x3001
                || cp == 0x2014 || cp == 0x2026;
    }

    private static boolean shouldInsertSpace(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return false;
        int a = left.codePointBefore(left.length());
        int b = right.codePointAt(0);
        return !(isCjk(a) && isCjk(b));
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private CircleAksOcrMerger() {}
}
