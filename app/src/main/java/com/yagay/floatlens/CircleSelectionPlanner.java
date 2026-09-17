package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Engine-neutral selection planner for Circle text gestures.
 *
 * <p>The OCR engine only provides text geometry. This planner is the single owner of selection
 * semantics and correction ROI. PP correction may refine text inside this plan, but must not choose
 * a different gesture range.</p>
 */
final class CircleSelectionPlanner {
    enum Mode { TAP, RANGE, FULL_LINES }

    static final class Plan {
        final Mode mode;
        final FLCircleSelection.Selection gesture;
        final OcrDocument baselineSelection;
        final List<Rect> rowBoundsScreen;
        final Rect selectionBoundsScreen;
        final Rect correctionBitmapRoi;

        Plan(Mode mode,
             FLCircleSelection.Selection gesture,
             OcrDocument baselineSelection,
             List<Rect> rowBoundsScreen,
             Rect selectionBoundsScreen,
             Rect correctionBitmapRoi) {
            this.mode = mode == null ? Mode.RANGE : mode;
            this.gesture = gesture;
            this.baselineSelection = baselineSelection;
            this.rowBoundsScreen = rowBoundsScreen == null ? List.of() : List.copyOf(rowBoundsScreen);
            this.selectionBoundsScreen = selectionBoundsScreen == null
                    ? new Rect() : new Rect(selectionBoundsScreen);
            this.correctionBitmapRoi = correctionBitmapRoi == null
                    ? new Rect() : new Rect(correctionBitmapRoi);
        }

        int rowCount() { return rowBoundsScreen.size(); }
    }

    private static final float TAP_TOLERANCE_DP = 10f;
    private static final float RANGE_CORRIDOR_DP = 10f;
    private static final float TAP_HALF_WIDTH_DP = 92f;
    private static final float TAP_HALF_HEIGHT_DP = 42f;
    private static final float RANGE_PAD_X_DP = 18f;
    private static final float RANGE_PAD_Y_DP = 16f;
    private static final float MIN_WIDTH_DP = 96f;
    private static final float MIN_HEIGHT_DP = 44f;
    private static final float ROW_MATCH_MIN_VERTICAL = 0.42f;
    private static final float ROW_MATCH_MIN_WIDTH_COVERAGE = 0.72f;

    static Plan plan(Context context,
                     FLCircleCapture.Frame frame,
                     FLCircleSelection.Selection gesture,
                     OcrDocument document) {
        if (context == null || frame == null || gesture == null || !usable(document)) return null;

        List<CircleGestureTextSelector.GroupHit> hits = CircleGestureTextSelector.hitGroups(
                context, frame, gesture, document, TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP);
        if (hits.isEmpty()) return null;

        Mode mode = gesture.kind == FLCircleSelection.Kind.TAP ? Mode.TAP : Mode.RANGE;
        OcrDocument selection = CircleGestureTextSelector.documentFromGroups(
                hits, document, "circle-plan-baseline");
        List<Rect> rows = lineBounds(selection);

        if (gesture.kind != FLCircleSelection.Kind.TAP) {
            int minLine = Integer.MAX_VALUE;
            int maxLine = Integer.MIN_VALUE;
            Set<Integer> lineIds = new HashSet<>();
            for (CircleGestureTextSelector.GroupHit hit : hits) {
                if (hit == null) continue;
                for (OcrDocument.CharUnit unit : hit.chars) {
                    if (unit == null) continue;
                    lineIds.add(unit.line());
                    minLine = Math.min(minLine, unit.line());
                    maxLine = Math.max(maxLine, unit.line());
                }
            }
            if (lineIds.size() >= 2 && minLine <= maxLine) {
                List<CircleGestureTextSelector.GroupHit> fullLineHits = fullLineHits(
                        document, minLine, maxLine);
                OcrDocument fullLines = CircleGestureTextSelector.documentFromGroups(
                        fullLineHits, document, "circle-plan-full-lines");
                if (usable(fullLines)) {
                    mode = Mode.FULL_LINES;
                    selection = fullLines;
                    rows = lineBounds(fullLines);
                }
            }
        }

        Rect selectionBounds = unionRows(rows);
        if (selectionBounds.isEmpty() && usable(selection)) {
            selectionBounds = unionChars(selection.chars());
        }
        Rect correctionRoi = correctionRoi(context, frame, gesture, selectionBounds);

        DiagnosticLog.i(context, "FL_CIRCLE_SELECTION_PLAN",
                "kind=" + gesture.kind
                        + " mode=" + mode
                        + " rows=" + rows.size()
                        + " baselineChars=" + (selection == null ? 0 : selection.chars().size())
                        + " selection=" + selectionBounds.toShortString()
                        + " correctionRoi=" + correctionRoi.toShortString()
                        + " owner=selection_planner");
        return usable(selection)
                ? new Plan(mode, gesture, selection, rows, selectionBounds, correctionRoi)
                : null;
    }

    static OcrDocument selectCorrection(Context context,
                                        FLCircleCapture.Frame frame,
                                        Plan plan,
                                        OcrDocument correctionScreen) {
        if (context == null || frame == null || plan == null || !usable(correctionScreen)) {
            return null;
        }

        if (plan.mode == Mode.TAP) {
            return CircleGestureTextSelector.selectDocument(context, frame, plan.gesture,
                    correctionScreen, TAP_TOLERANCE_DP, RANGE_CORRIDOR_DP,
                    "circle-plan-correction-tap");
        }

        if (plan.mode == Mode.FULL_LINES) {
            ArrayList<CircleGestureTextSelector.GroupHit> matchedGroups = new ArrayList<>();
            ArrayList<OcrDocument.Line> used = new ArrayList<>();
            int matchedRows = 0;
            for (Rect targetRow : plan.rowBoundsScreen) {
                OcrDocument.Line best = null;
                float bestScore = -1f;
                for (OcrDocument.Line line : correctionScreen.lines()) {
                    if (line == null || line.chars().isEmpty() || used.contains(line)) continue;
                    Rect bounds = line.bounds();
                    float vertical = axisOverlapRatio(
                            targetRow.top, targetRow.bottom, bounds.top, bounds.bottom);
                    if (vertical < ROW_MATCH_MIN_VERTICAL) continue;
                    float widthCoverage = horizontalCoverage(targetRow, bounds);
                    float score = vertical * 2f + widthCoverage;
                    if (score > bestScore) {
                        bestScore = score;
                        best = line;
                    }
                }
                if (best == null) {
                    DiagnosticLog.i(context, "FL_CIRCLE_CORRECTION_PLAN",
                            "mode=FULL_LINES accepted=false reason=row_missing target="
                                    + targetRow.toShortString());
                    return null;
                }
                Rect bestBounds = best.bounds();
                float widthCoverage = horizontalCoverage(targetRow, bestBounds);
                if (widthCoverage < ROW_MATCH_MIN_WIDTH_COVERAGE) {
                    DiagnosticLog.i(context, "FL_CIRCLE_CORRECTION_PLAN",
                            "mode=FULL_LINES accepted=false reason=row_width_coverage"
                                    + " coverage=" + widthCoverage
                                    + " target=" + targetRow.toShortString()
                                    + " correction=" + bestBounds.toShortString());
                    return null;
                }
                used.add(best);
                List<CircleGestureTextSelector.GroupHit> lineGroups = groupsFromChars(best.chars());
                if (lineGroups.isEmpty()) return null;
                matchedGroups.addAll(lineGroups);
                matchedRows++;
            }
            OcrDocument selected = CircleGestureTextSelector.documentFromGroups(
                    matchedGroups, correctionScreen, "circle-plan-correction-full-lines");
            DiagnosticLog.i(context, "FL_CIRCLE_CORRECTION_PLAN",
                    "mode=FULL_LINES accepted=" + usable(selected)
                            + " rows=" + matchedRows
                            + " groups=" + matchedGroups.size()
                            + " chars=" + (selected == null ? 0 : selected.chars().size())
                            + " policy=preserve_planned_rows_and_groups");
            return selected;
        }

        // Single-row highlight/scribble: use the baseline spatial range, not a new gesture-derived
        // range from the correction engine.
        Rect target = new Rect(plan.selectionBoundsScreen);
        if (target.isEmpty()) return null;
        int pad = Math.max(1, Math.round(4f * context.getResources().getDisplayMetrics().density));
        target.inset(-pad, -pad);
        ArrayList<CircleGestureTextSelector.GroupHit> groups = new ArrayList<>();
        for (OcrDocument.Line line : correctionScreen.lines()) {
            if (line == null || line.chars().isEmpty()) continue;
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit unit : line.chars()) {
                if (unit == null || unit.bounds().isEmpty() || unit.text().isBlank()) continue;
                Rect r = unit.bounds();
                if (target.contains(r.centerX(), r.centerY()) || Rect.intersects(target, r)) {
                    chars.add(unit);
                }
            }
            groups.addAll(groupsFromChars(chars));
        }
        OcrDocument selected = CircleGestureTextSelector.documentFromGroups(
                groups, correctionScreen, "circle-plan-correction-range");
        DiagnosticLog.i(context, "FL_CIRCLE_CORRECTION_PLAN",
                "mode=RANGE accepted=" + usable(selected)
                        + " groups=" + groups.size()
                        + " chars=" + (selected == null ? 0 : selected.chars().size())
                        + " policy=preserve_baseline_spatial_range");
        return selected;
    }

    private static List<CircleGestureTextSelector.GroupHit> fullLineHits(OcrDocument document,
                                                                          int minLine,
                                                                          int maxLine) {
        ArrayList<CircleGestureTextSelector.GroupHit> out = new ArrayList<>();
        for (OcrDocument.Line line : document.lines()) {
            if (line == null || line.chars().isEmpty()) continue;
            int lineId = Integer.MAX_VALUE;
            for (OcrDocument.CharUnit unit : line.chars()) {
                if (unit != null) lineId = Math.min(lineId, unit.line());
            }
            if (lineId < minLine || lineId > maxLine) continue;
            out.addAll(groupsFromChars(line.chars()));
        }
        return List.copyOf(out);
    }

    private static List<CircleGestureTextSelector.GroupHit> groupsFromChars(
            List<OcrDocument.CharUnit> chars) {
        if (chars == null || chars.isEmpty()) return List.of();
        Map<Integer, ArrayList<OcrDocument.CharUnit>> byGroup = new LinkedHashMap<>();
        for (OcrDocument.CharUnit unit : chars) {
            if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
            byGroup.computeIfAbsent(unit.group(), ignored -> new ArrayList<>()).add(unit);
        }
        ArrayList<CircleGestureTextSelector.GroupHit> out = new ArrayList<>();
        for (ArrayList<OcrDocument.CharUnit> groupChars : byGroup.values()) {
            CircleGestureTextSelector.GroupHit hit = groupFromChars(groupChars);
            if (hit != null) out.add(hit);
        }
        return List.copyOf(out);
    }

    private static CircleGestureTextSelector.GroupHit groupFromChars(
            ArrayList<OcrDocument.CharUnit> chars) {
        if (chars == null || chars.isEmpty()) return null;
        ArrayList<OcrDocument.CharUnit> clean = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Rect bounds = null;
        float confidence = 0f;
        for (OcrDocument.CharUnit unit : chars) {
            if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
            clean.add(unit);
            text.append(unit.text());
            Rect r = unit.bounds();
            if (bounds == null) bounds = new Rect(r); else bounds.union(r);
            confidence += unit.confidence();
        }
        if (clean.isEmpty() || bounds == null || bounds.isEmpty()) return null;
        return new CircleGestureTextSelector.GroupHit(
                text.toString(), bounds, clean, confidence / clean.size());
    }

    private static List<Rect> lineBounds(OcrDocument document) {
        if (!usable(document)) return List.of();
        ArrayList<Rect> rows = new ArrayList<>();
        for (OcrDocument.Line line : document.lines()) {
            if (line == null || line.bounds().isEmpty()) continue;
            rows.add(line.bounds());
        }
        return List.copyOf(rows);
    }

    private static Rect unionRows(List<Rect> rows) {
        Rect out = null;
        if (rows != null) {
            for (Rect row : rows) {
                if (row == null || row.isEmpty()) continue;
                if (out == null) out = new Rect(row); else out.union(row);
            }
        }
        return out == null ? new Rect() : out;
    }

    private static Rect unionChars(List<OcrDocument.CharUnit> chars) {
        Rect out = null;
        if (chars != null) {
            for (OcrDocument.CharUnit unit : chars) {
                if (unit == null || unit.bounds().isEmpty()) continue;
                Rect r = unit.bounds();
                if (out == null) out = new Rect(r); else out.union(r);
            }
        }
        return out == null ? new Rect() : out;
    }

    private static Rect correctionRoi(Context app,
                                      FLCircleCapture.Frame frame,
                                      FLCircleSelection.Selection gesture,
                                      Rect selectionBoundsScreen) {
        int width = frame.bitmap.getWidth();
        int height = frame.bitmap.getHeight();
        if (gesture.kind == FLCircleSelection.Kind.TAP) {
            int halfW = bitmapPxForDp(app, frame, TAP_HALF_WIDTH_DP);
            int halfH = bitmapPxForDp(app, frame, TAP_HALF_HEIGHT_DP);
            int cx = Math.round(gesture.focus.x);
            int cy = Math.round(gesture.focus.y);
            return clampRect(new Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH),
                    width, height);
        }

        Rect base = selectionBoundsScreen == null || selectionBoundsScreen.isEmpty()
                ? FLCircleSelection.exactRectAndClamp(gesture.bounds, width, height)
                : frame.screenRectToBitmap(selectionBoundsScreen);
        if (base.isEmpty()) return new Rect();
        int padX = bitmapPxForDp(app, frame, RANGE_PAD_X_DP);
        int padY = bitmapPxForDp(app, frame, RANGE_PAD_Y_DP);
        Rect expanded = clampRect(new Rect(base.left - padX, base.top - padY,
                base.right + padX, base.bottom + padY), width, height);
        int minW = bitmapPxForDp(app, frame, MIN_WIDTH_DP);
        int minH = bitmapPxForDp(app, frame, MIN_HEIGHT_DP);
        return ensureMinimumRect(expanded, width, height, minW, minH);
    }

    private static Rect ensureMinimumRect(Rect source, int width, int height, int minW, int minH) {
        if (source == null || source.isEmpty()) return new Rect();
        int targetW = Math.min(width, Math.max(source.width(), Math.max(1, minW)));
        int targetH = Math.min(height, Math.max(source.height(), Math.max(1, minH)));
        int left = Math.max(0, Math.min(source.centerX() - targetW / 2, width - targetW));
        int top = Math.max(0, Math.min(source.centerY() - targetH / 2, height - targetH));
        return new Rect(left, top, left + targetW, top + targetH);
    }

    private static Rect clampRect(Rect source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return new Rect();
        int left = Math.max(0, Math.min(width - 1, source.left));
        int top = Math.max(0, Math.min(height - 1, source.top));
        int right = Math.max(left + 1, Math.min(width, source.right));
        int bottom = Math.max(top + 1, Math.min(height, source.bottom));
        return new Rect(left, top, right, bottom);
    }

    private static int bitmapPxForDp(Context app, FLCircleCapture.Frame frame, float dp) {
        float density = app.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(frame.transform.screenDistanceToBitmap(
                Math.max(1f, dp * density))));
    }

    private static float axisOverlapRatio(int aStart, int aEnd, int bStart, int bEnd) {
        int overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
        int smaller = Math.max(1, Math.min(Math.max(1, aEnd - aStart),
                Math.max(1, bEnd - bStart)));
        return overlap / (float) smaller;
    }

    private static float horizontalCoverage(Rect target, Rect candidate) {
        int overlap = Math.max(0, Math.min(target.right, candidate.right)
                - Math.max(target.left, candidate.left));
        return overlap / (float) Math.max(1, target.width());
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isScreenSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private CircleSelectionPlanner() {}
}
