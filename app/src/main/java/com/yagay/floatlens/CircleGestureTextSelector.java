package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selects semantic OCR/View groups using the user's actual Circle gesture path in SCREEN space. */
final class CircleGestureTextSelector {
    static final class GroupHit {
        final String text;
        final Rect bounds;
        final List<OcrDocument.CharUnit> chars;
        final float confidence;

        GroupHit(String text, Rect bounds, List<OcrDocument.CharUnit> chars, float confidence) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.chars = chars == null ? List.of() : List.copyOf(chars);
            this.confidence = confidence;
        }
    }

    static List<GroupHit> hitGroups(Context context,
                                    GoogleCircleCapture.Frame frame,
                                    GoogleCircleSelection.Selection gesture,
                                    OcrDocument document,
                                    float tapToleranceDp,
                                    float corridorDp) {
        if (context == null || frame == null || gesture == null || !usable(document)) {
            return List.of();
        }

        List<GroupHit> groups = buildGroups(document);
        if (groups.isEmpty()) return List.of();

        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            PointF point = frame.bitmapPointToScreen(gesture.focus.x, gesture.focus.y);
            float tolerance = Math.max(0f, tapToleranceDp * ScreenGeometry.density(context));
            GroupHit exact = null;
            long exactArea = Long.MAX_VALUE;
            GroupHit nearest = null;
            float nearestDistance = Float.MAX_VALUE;
            long nearestArea = Long.MAX_VALUE;

            for (GroupHit group : groups) {
                boolean contains = false;
                float distance = Float.MAX_VALUE;
                long area = area(group.bounds);
                for (OcrDocument.CharUnit unit : group.chars) {
                    Rect r = unit.bounds();
                    if (r.contains(Math.round(point.x), Math.round(point.y))) {
                        contains = true;
                        distance = 0f;
                        break;
                    }
                    distance = Math.min(distance, pointDistanceToRect(point, r));
                }
                if (contains) {
                    if (exact == null || area < exactArea) {
                        exact = group;
                        exactArea = area;
                    }
                } else if (tolerance > 0f && distance <= tolerance
                        && (distance < nearestDistance
                        || (Math.abs(distance - nearestDistance) < 0.5f && area < nearestArea))) {
                    nearest = group;
                    nearestDistance = distance;
                    nearestArea = area;
                }
            }
            if (exact != null) return List.of(exact);
            return nearest == null ? List.of() : List.of(nearest);
        }

        float corridor = Math.max(0f, corridorDp * ScreenGeometry.density(context));
        List<PointF> path = screenPath(frame, gesture);
        Rect fallback = gestureScreenBounds(frame, gesture);
        ArrayList<GroupHit> selected = new ArrayList<>();
        for (GroupHit group : groups) {
            boolean hit = false;
            for (OcrDocument.CharUnit unit : group.chars) {
                Rect rect = unit.bounds();
                if (pathHitsRect(path, rect, corridor, fallback)) {
                    hit = true;
                    break;
                }
            }
            if (hit) selected.add(group);
        }
        selected.sort((a, b) -> compareVisual(a.bounds, b.bounds));
        return List.copyOf(selected);
    }

    static OcrDocument selectDocument(Context context,
                                      GoogleCircleCapture.Frame frame,
                                      GoogleCircleSelection.Selection gesture,
                                      OcrDocument document,
                                      float tapToleranceDp,
                                      float corridorDp,
                                      String engine) {
        List<GroupHit> hits = hitGroups(context, frame, gesture, document,
                tapToleranceDp, corridorDp);
        return documentFromGroups(hits, document, engine);
    }

    static OcrDocument documentFromGroups(List<GroupHit> groups,
                                          OcrDocument template,
                                          String engine) {
        if (groups == null || groups.isEmpty() || template == null) return null;
        ArrayList<GroupHit> ordered = new ArrayList<>(groups);
        ordered.sort((a, b) -> compareVisual(a.bounds, b.bounds));

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int groupId = 0;
        int order = 0;
        float confidenceSum = 0f;
        int confidenceCount = 0;

        for (GroupHit group : ordered) {
            if (group == null || group.chars.isEmpty() || group.bounds.isEmpty()) continue;
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Rect union = null;
            for (OcrDocument.CharUnit source : group.chars) {
                if (source == null || source.text().isBlank() || source.bounds().isEmpty()) continue;
                Rect bounds = source.bounds();
                chars.add(new OcrDocument.CharUnit(source.text(), bounds, source.confidence(),
                        lineId, groupId, order++));
                text.append(source.text());
                if (union == null) union = new Rect(bounds); else union.union(bounds);
                confidenceSum += source.confidence();
                confidenceCount++;
            }
            if (chars.isEmpty() || union == null || union.isEmpty()) continue;
            String value = group.text.isBlank() ? text.toString() : group.text;
            lines.add(new OcrDocument.Line(value, union, group.confidence, chars));
            blocks.add(value);
            if (full.length() > 0) full.append('\n');
            full.append(value);
            lineId++;
            groupId++;
        }
        if (lines.isEmpty()) return null;
        float confidence = confidenceCount == 0 ? 0f : confidenceSum / confidenceCount;
        return OcrDocument.screenSpace(full.toString(), blocks, lines,
                engine == null ? "gesture-selected" : engine,
                confidence, template.score(), template.imageWidth(), template.imageHeight());
    }

    private static List<GroupHit> buildGroups(OcrDocument document) {
        ArrayList<GroupHit> out = new ArrayList<>();
        for (OcrDocument.Line line : document.lines()) {
            if (line == null || line.chars().isEmpty()) continue;
            Map<Integer, ArrayList<OcrDocument.CharUnit>> byGroup = new LinkedHashMap<>();
            for (OcrDocument.CharUnit unit : line.chars()) {
                if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
                byGroup.computeIfAbsent(unit.group(), ignored -> new ArrayList<>()).add(unit);
            }
            for (ArrayList<OcrDocument.CharUnit> chars : byGroup.values()) {
                if (chars.isEmpty()) continue;
                StringBuilder text = new StringBuilder();
                Rect union = null;
                float confidence = 0f;
                for (OcrDocument.CharUnit unit : chars) {
                    text.append(unit.text());
                    Rect r = unit.bounds();
                    if (union == null) union = new Rect(r); else union.union(r);
                    confidence += unit.confidence();
                }
                if (union == null || union.isEmpty()) continue;
                out.add(new GroupHit(text.toString(), union, chars, confidence / chars.size()));
            }
        }
        return List.copyOf(out);
    }

    private static List<PointF> screenPath(GoogleCircleCapture.Frame frame,
                                           GoogleCircleSelection.Selection gesture) {
        if (gesture.points == null || gesture.points.isEmpty()) return List.of();
        ArrayList<PointF> out = new ArrayList<>(gesture.points.size());
        for (PointF point : gesture.points) {
            if (point == null) continue;
            out.add(frame.bitmapPointToScreen(point.x, point.y));
        }
        return List.copyOf(out);
    }

    private static boolean pathHitsRect(List<PointF> path, Rect rect, float corridor, Rect fallback) {
        if (rect == null || rect.isEmpty()) return false;
        RectF expanded = new RectF(rect);
        if (corridor > 0f) expanded.inset(-corridor, -corridor);
        if (path != null && path.size() >= 2) {
            for (int i = 1; i < path.size(); i++) {
                if (segmentIntersectsRect(path.get(i - 1), path.get(i), expanded)) return true;
            }
            return false;
        }
        RectF fallbackRect = fallback == null ? new RectF() : new RectF(fallback);
        if (corridor > 0f && !fallbackRect.isEmpty()) fallbackRect.inset(-corridor, -corridor);
        return !fallbackRect.isEmpty() && RectF.intersects(fallbackRect, expanded);
    }

    private static boolean segmentIntersectsRect(PointF a, PointF b, RectF r) {
        if (a == null || b == null || r == null || r.isEmpty()) return false;
        if (r.contains(a.x, a.y) || r.contains(b.x, b.y)) return true;
        return segmentsIntersect(a.x, a.y, b.x, b.y, r.left, r.top, r.right, r.top)
                || segmentsIntersect(a.x, a.y, b.x, b.y, r.right, r.top, r.right, r.bottom)
                || segmentsIntersect(a.x, a.y, b.x, b.y, r.right, r.bottom, r.left, r.bottom)
                || segmentsIntersect(a.x, a.y, b.x, b.y, r.left, r.bottom, r.left, r.top);
    }

    private static boolean segmentsIntersect(float ax, float ay, float bx, float by,
                                             float cx, float cy, float dx, float dy) {
        float d1 = direction(cx, cy, dx, dy, ax, ay);
        float d2 = direction(cx, cy, dx, dy, bx, by);
        float d3 = direction(ax, ay, bx, by, cx, cy);
        float d4 = direction(ax, ay, bx, by, dx, dy);
        final float epsilon = 0.001f;
        if (((d1 > epsilon && d2 < -epsilon) || (d1 < -epsilon && d2 > epsilon))
                && ((d3 > epsilon && d4 < -epsilon) || (d3 < -epsilon && d4 > epsilon))) {
            return true;
        }
        return Math.abs(d1) <= epsilon && onSegment(cx, cy, dx, dy, ax, ay)
                || Math.abs(d2) <= epsilon && onSegment(cx, cy, dx, dy, bx, by)
                || Math.abs(d3) <= epsilon && onSegment(ax, ay, bx, by, cx, cy)
                || Math.abs(d4) <= epsilon && onSegment(ax, ay, bx, by, dx, dy);
    }

    private static float direction(float ax, float ay, float bx, float by, float px, float py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static boolean onSegment(float ax, float ay, float bx, float by, float px, float py) {
        return px >= Math.min(ax, bx) - 0.001f && px <= Math.max(ax, bx) + 0.001f
                && py >= Math.min(ay, by) - 0.001f && py <= Math.max(ay, by) + 0.001f;
    }

    private static float pointDistanceToRect(PointF point, Rect rect) {
        float dx = point.x < rect.left ? rect.left - point.x
                : point.x > rect.right ? point.x - rect.right : 0f;
        float dy = point.y < rect.top ? rect.top - point.y
                : point.y > rect.bottom ? point.y - rect.bottom : 0f;
        return (float) Math.hypot(dx, dy);
    }

    private static Rect gestureScreenBounds(GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        Rect bitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
    }

    private static int compareVisual(Rect a, Rect b) {
        int tolerance = Math.max(3,
                Math.min(Math.max(1, a.height()), Math.max(1, b.height())) / 2);
        int dy = a.centerY() - b.centerY();
        if (Math.abs(dy) > tolerance) return Integer.compare(a.centerY(), b.centerY());
        return Integer.compare(a.left, b.left);
    }

    private static long area(Rect rect) {
        return rect == null || rect.isEmpty() ? Long.MAX_VALUE
                : Math.max(1L, (long) rect.width() * rect.height());
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isScreenSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private CircleGestureTextSelector() {}
}
