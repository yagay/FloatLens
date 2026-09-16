package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Chooses OCR text only around the current gesture; independent preindex passes stay unmodified. */
final class CircleOcrConsensus {
    static final class Result {
        final OcrDocument document;
        final int passHits;
        final int clusters;
        /** Number of OCR passes that hit the same spatial target, regardless of recognized text. */
        final int maxSupport;
        /** Maximum number of independent passes that agree on the exact normalized chosen text. */
        final int maxTextSupport;
        /** True when at least one spatial cluster contains two or more different recognized texts. */
        final boolean textConflict;
        /** Compact diagnostic summary of source=text alternatives near the gesture. */
        final String candidateSummary;

        Result(OcrDocument document, int passHits, int clusters, int maxSupport,
               int maxTextSupport, boolean textConflict, String candidateSummary) {
            this.document = document;
            this.passHits = passHits;
            this.clusters = clusters;
            this.maxSupport = maxSupport;
            this.maxTextSupport = maxTextSupport;
            this.textConflict = textConflict;
            this.candidateSummary = candidateSummary == null ? "" : candidateSummary;
        }
    }

    private static final class Candidate {
        final CircleOcrIndex.Entry entry;
        final CircleGestureTextSelector.GroupHit hit;

        Candidate(CircleOcrIndex.Entry entry, CircleGestureTextSelector.GroupHit hit) {
            this.entry = entry;
            this.hit = hit;
        }
    }

    private static final class Cluster {
        final ArrayList<Candidate> members = new ArrayList<>();
        final Set<String> sources = new HashSet<>();

        boolean canAdd(Candidate candidate, float density) {
            if (candidate == null || sources.contains(candidate.entry.source)) return false;
            for (Candidate member : members) {
                if (samePlace(member.hit.bounds, candidate.hit.bounds, density)) return true;
            }
            return false;
        }

        void add(Candidate candidate) {
            members.add(candidate);
            sources.add(candidate.entry.source);
        }

        int support() { return sources.size(); }

        int exactTextSupport(Candidate candidate) {
            if (candidate == null) return 0;
            String key = normalize(candidate.hit.text);
            if (key.isEmpty()) return 0;
            HashSet<String> agreeingSources = new HashSet<>();
            for (Candidate member : members) {
                if (key.equals(normalize(member.hit.text))) agreeingSources.add(member.entry.source);
            }
            return agreeingSources.size();
        }

        int distinctTextCount() {
            HashSet<String> values = new HashSet<>();
            for (Candidate member : members) {
                String key = normalize(member.hit.text);
                if (!key.isEmpty()) values.add(key);
            }
            return values.size();
        }
    }

    static Result resolve(Context context,
                          GoogleCircleCapture.Frame frame,
                          GoogleCircleSelection.Selection gesture,
                          CircleOcrIndex index,
                          float tapToleranceDp,
                          float corridorDp) {
        if (context == null || frame == null || gesture == null || index == null || index.isEmpty()) {
            return new Result(null, 0, 0, 0, 0, false, "");
        }

        ArrayList<Candidate> candidates = new ArrayList<>();
        int passHits = 0;
        for (CircleOcrIndex.Entry entry : index.entries()) {
            List<CircleGestureTextSelector.GroupHit> hits = CircleGestureTextSelector.hitGroups(
                    context, frame, gesture, entry.document, tapToleranceDp, corridorDp);
            if (!hits.isEmpty()) passHits++;
            for (CircleGestureTextSelector.GroupHit hit : hits) {
                if (hit == null || hit.text.isBlank() || hit.bounds.isEmpty()) continue;
                candidates.add(new Candidate(entry, hit));
            }
        }
        if (candidates.isEmpty()) return new Result(null, passHits, 0, 0, 0, false, "");

        float density = ScreenGeometry.density(context);
        ArrayList<Cluster> clusters = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Cluster match = null;
            for (Cluster cluster : clusters) {
                if (cluster.canAdd(candidate, density)) {
                    match = cluster;
                    break;
                }
            }
            if (match == null) {
                match = new Cluster();
                clusters.add(match);
            }
            match.add(candidate);
        }

        ArrayList<Chosen> chosen = new ArrayList<>();
        int maxSupport = 0;
        int maxTextSupport = 0;
        boolean textConflict = false;
        StringBuilder alternatives = new StringBuilder();
        for (Cluster cluster : clusters) {
            Candidate best = choose(cluster, frame.screenBounds);
            if (best == null) continue;
            maxSupport = Math.max(maxSupport, cluster.support());
            maxTextSupport = Math.max(maxTextSupport, cluster.exactTextSupport(best));
            textConflict |= cluster.distinctTextCount() > 1;
            appendClusterSummary(alternatives, cluster);
            chosen.add(new Chosen(best, score(best, cluster, frame.screenBounds)));
        }
        if (chosen.isEmpty()) {
            return new Result(null, passHits, clusters.size(), maxSupport,
                    maxTextSupport, textConflict, alternatives.toString());
        }

        // A transitive spatial cluster can still leave two nearly identical representatives. Keep
        // only the better one without modifying its original character geometry.
        ArrayList<Chosen> stable = new ArrayList<>();
        for (Chosen incoming : chosen) {
            int duplicate = findChosenDuplicate(stable, incoming, density);
            if (duplicate < 0) stable.add(incoming);
            else if (incoming.score > stable.get(duplicate).score) stable.set(duplicate, incoming);
        }

        ArrayList<CircleGestureTextSelector.GroupHit> groups = new ArrayList<>();
        for (Chosen item : stable) groups.add(item.candidate.hit);
        OcrDocument template = index.fullFrameDocument();
        if (template == null && !index.entries().isEmpty()) template = index.entries().get(0).document;
        OcrDocument document = CircleGestureTextSelector.documentFromGroups(groups, template,
                "gesture-consensus");
        return new Result(document, passHits, clusters.size(), maxSupport,
                maxTextSupport, textConflict, alternatives.toString());
    }

    private static final class Chosen {
        final Candidate candidate;
        final double score;

        Chosen(Candidate candidate, double score) {
            this.candidate = candidate;
            this.score = score;
        }
    }

    private static Candidate choose(Cluster cluster, Rect frameBounds) {
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : cluster.members) {
            double value = score(candidate, cluster, frameBounds);
            if (value > bestScore) {
                best = candidate;
                bestScore = value;
            }
        }
        return best;
    }

    private static double score(Candidate candidate, Cluster cluster, Rect frameBounds) {
        String key = normalize(candidate.hit.text);
        double fuzzyAgreement = 0d;
        Set<String> exactSources = new HashSet<>();
        Set<String> fuzzySources = new HashSet<>();

        for (Candidate other : cluster.members) {
            String otherKey = normalize(other.hit.text);
            if (otherKey.isEmpty()) continue;
            float similarity = textSimilarity(key, otherKey);
            if (key.equals(otherKey)) exactSources.add(other.entry.source);
            if (fuzzySources.add(other.entry.source)) fuzzyAgreement += similarity;
        }
        int exactSupport = exactSources.size();

        float confidence = candidate.hit.confidence;
        if (!Float.isFinite(confidence)) confidence = 0f;
        confidence = Math.max(0f, Math.min(1f, confidence));
        boolean clipped = touchesInternalCoverage(candidate, frameBounds);
        int chars = candidate.hit.chars.size();

        return exactSupport * 4.0d
                + fuzzyAgreement * 1.15d
                + confidence
                + Math.min(20, chars) * 0.012d
                + (candidate.entry.fullFrame ? 0.08d : 0d)
                + (clipped ? -0.65d : 0.18d);
    }

    private static boolean touchesInternalCoverage(Candidate candidate, Rect frameBounds) {
        if (candidate == null || candidate.entry.fullFrame || candidate.entry.coverage.isEmpty()
                || frameBounds == null || frameBounds.isEmpty()) return false;
        Rect coverage = candidate.entry.coverage;
        Rect bounds = candidate.hit.bounds;
        int margin = Math.max(3, Math.min(14, Math.max(1, bounds.height()) / 3));
        boolean left = coverage.left > frameBounds.left && bounds.left <= coverage.left + margin;
        boolean top = coverage.top > frameBounds.top && bounds.top <= coverage.top + margin;
        boolean right = coverage.right < frameBounds.right && bounds.right >= coverage.right - margin;
        boolean bottom = coverage.bottom < frameBounds.bottom && bounds.bottom >= coverage.bottom - margin;
        return left || top || right || bottom;
    }

    private static boolean samePlace(Rect a, Rect b, float density) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        float vertical = axisOverlap(a.top, a.bottom, b.top, b.bottom);
        if (vertical < 0.55f) return false;
        float overlap = overlapRatio(a, b);
        if (overlap >= 0.24f) return true;

        float horizontal = axisOverlap(a.left, a.right, b.left, b.right);
        if (horizontal < 0.35f) return false;
        float dx = a.centerX() - b.centerX();
        float dy = a.centerY() - b.centerY();
        float distance = (float) Math.hypot(dx, dy);
        float gate = Math.max(8f * density,
                Math.min(Math.max(a.width(), b.width()), Math.max(a.height(), b.height())) * 0.55f);
        return distance <= gate;
    }

    private static int findChosenDuplicate(List<Chosen> accepted, Chosen incoming, float density) {
        for (int i = 0; i < accepted.size(); i++) {
            Chosen existing = accepted.get(i);
            if (!samePlace(existing.candidate.hit.bounds, incoming.candidate.hit.bounds, density)) continue;
            float similarity = textSimilarity(normalize(existing.candidate.hit.text),
                    normalize(incoming.candidate.hit.text));
            if (similarity >= 0.55f) return i;
        }
        return -1;
    }

    private static void appendClusterSummary(StringBuilder out, Cluster cluster) {
        if (out == null || cluster == null || out.length() >= 220) return;
        if (out.length() > 0) out.append(" | ");
        int added = 0;
        for (Candidate member : cluster.members) {
            if (added++ > 0) out.append(',');
            out.append(member.entry.source).append('=');
            String text = member.hit.text == null ? "" : member.hit.text
                    .replace('\n', ' ').replace('\r', ' ').trim();
            if (text.length() > 24) text = text.substring(0, 24) + "…";
            out.append(text);
            if (out.length() >= 220) break;
        }
    }

    private static float overlapRatio(Rect a, Rect b) {
        Rect intersection = new Rect();
        if (!intersection.setIntersect(a, b)) return 0f;
        long overlap = Math.max(0L, (long) intersection.width() * intersection.height());
        long smaller = Math.max(1L, Math.min(area(a), area(b)));
        return overlap / (float) smaller;
    }

    private static float axisOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        int overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
        int smaller = Math.max(1, Math.min(Math.max(1, aEnd - aStart), Math.max(1, bEnd - bStart)));
        return overlap / (float) smaller;
    }

    private static long area(Rect rect) {
        return rect == null || rect.isEmpty() ? 0L
                : Math.max(1L, (long) rect.width() * rect.height());
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || isCjk(cp)) out.appendCodePoint(cp);
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
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private CircleOcrConsensus() {}
}
