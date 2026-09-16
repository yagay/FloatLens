package com.yagay.floatlens;

import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;

/**
 * Device-independent mapping between two rectangular coordinate spaces.
 *
 * <p>The mapper owns the transform in both directions. Callers never manually add status-bar
 * offsets or multiply by screen/bitmap ratios. This keeps screenshot, overlay, crop and OCR
 * geometry consistent across different resolutions, densities, rotations, cutouts, navigation
 * modes and non-zero display origins.</p>
 */
final class CoordinateMapper {
    private final RectF sourceBounds;
    private final RectF targetBounds;
    private final Matrix sourceToTarget = new Matrix();
    private final Matrix targetToSource = new Matrix();
    private final boolean valid;

    CoordinateMapper(RectF sourceBounds, RectF targetBounds) {
        this.sourceBounds = sourceBounds == null ? new RectF() : new RectF(sourceBounds);
        this.targetBounds = targetBounds == null ? new RectF() : new RectF(targetBounds);
        boolean ok = !this.sourceBounds.isEmpty() && !this.targetBounds.isEmpty();
        if (ok) {
            ok = sourceToTarget.setRectToRect(
                    this.sourceBounds, this.targetBounds, Matrix.ScaleToFit.FILL)
                    && sourceToTarget.invert(targetToSource);
        }
        valid = ok;
    }

    static CoordinateMapper bitmapToScreen(Rect screenBounds, int bitmapWidth, int bitmapHeight) {
        Rect screen = screenBounds == null ? new Rect() : new Rect(screenBounds);
        if (screen.isEmpty()) {
            screen.set(0, 0, Math.max(1, bitmapWidth), Math.max(1, bitmapHeight));
        }
        return new CoordinateMapper(
                new RectF(0f, 0f, Math.max(1, bitmapWidth), Math.max(1, bitmapHeight)),
                new RectF(screen));
    }

    boolean isValid() { return valid; }
    RectF sourceBounds() { return new RectF(sourceBounds); }
    RectF targetBounds() { return new RectF(targetBounds); }

    PointF mapPoint(float x, float y) {
        return mapPointInternal(sourceToTarget, sourceBounds, x, y, false);
    }

    PointF mapPointClamped(float x, float y) {
        return mapPointInternal(sourceToTarget, sourceBounds, x, y, true);
    }

    PointF unmapPoint(float x, float y) {
        return mapPointInternal(targetToSource, targetBounds, x, y, false);
    }

    PointF unmapPointClamped(float x, float y) {
        return mapPointInternal(targetToSource, targetBounds, x, y, true);
    }

    RectF mapRectF(RectF rect) {
        return mapRectInternal(sourceToTarget, sourceBounds, targetBounds, rect);
    }

    RectF unmapRectF(RectF rect) {
        return mapRectInternal(targetToSource, targetBounds, sourceBounds, rect);
    }

    Rect mapRectOut(Rect rect) {
        if (rect == null || rect.isEmpty()) return new Rect();
        return roundOut(mapRectF(new RectF(rect)), targetBounds);
    }

    Rect mapRectOut(RectF rect) {
        return roundOut(mapRectF(rect), targetBounds);
    }

    Rect unmapRectOut(Rect rect) {
        if (rect == null || rect.isEmpty()) return new Rect();
        return roundOut(unmapRectF(new RectF(rect)), sourceBounds);
    }

    Rect unmapRectOut(RectF rect) {
        return roundOut(unmapRectF(rect), sourceBounds);
    }

    /** Maps an OCR document from this mapper's source space into its target space. */
    OcrDocument mapDocument(OcrDocument document, boolean targetIsScreenSpace,
                            int targetWidth, int targetHeight, String enginePrefix) {
        if (document == null) return null;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int order = 0;

        for (OcrDocument.Line line : document.lines()) {
            if (line == null) continue;
            Rect mappedLine = mapRectOut(line.bounds());
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null || c.text().isBlank()) continue;
                Rect mapped = mapRectOut(c.bounds());
                if (mapped.isEmpty()) continue;
                chars.add(new OcrDocument.CharUnit(c.text(), mapped, c.confidence(),
                        lineId, c.group(), order++));
            }
            if (chars.isEmpty()) continue;
            if (mappedLine.isEmpty()) {
                mappedLine = new Rect(chars.get(0).bounds());
                for (int i = 1; i < chars.size(); i++) mappedLine.union(chars.get(i).bounds());
            }
            lines.add(new OcrDocument.Line(line.text(), mappedLine, line.confidence(), chars));
            lineId++;
        }

        String engine = (enginePrefix == null ? "" : enginePrefix) + document.engine();
        int width = Math.max(1, targetWidth);
        int height = Math.max(1, targetHeight);
        if (targetIsScreenSpace) {
            return OcrDocument.screenSpace(document.fullText(), document.blocks(), lines,
                    engine, document.confidence(), document.score(), width, height);
        }
        return new OcrDocument(document.fullText(), document.blocks(), lines,
                engine, document.confidence(), document.score(), width, height);
    }

    private PointF mapPointInternal(Matrix matrix, RectF inputBounds,
                                    float x, float y, boolean clamp) {
        if (!valid) return new PointF();
        float px = x;
        float py = y;
        if (clamp) {
            px = Math.max(inputBounds.left, Math.min(inputBounds.right, px));
            py = Math.max(inputBounds.top, Math.min(inputBounds.bottom, py));
        }
        float[] point = {px, py};
        matrix.mapPoints(point);
        return new PointF(point[0], point[1]);
    }

    private RectF mapRectInternal(Matrix matrix, RectF inputBounds, RectF outputBounds,
                                  RectF rect) {
        if (!valid || rect == null || rect.isEmpty()) return new RectF();
        RectF clipped = new RectF(rect);
        if (!clipped.intersect(inputBounds)) return new RectF();
        RectF mapped = new RectF(clipped);
        matrix.mapRect(mapped);
        if (!mapped.intersect(outputBounds)) return new RectF();
        return mapped;
    }

    private static Rect roundOut(RectF rect, RectF clampBounds) {
        if (rect == null || rect.isEmpty()) return new Rect();
        int left = (int) Math.floor(rect.left);
        int top = (int) Math.floor(rect.top);
        int right = (int) Math.ceil(rect.right);
        int bottom = (int) Math.ceil(rect.bottom);
        Rect out = new Rect(left, top, right, bottom);
        Rect clamp = new Rect((int) Math.floor(clampBounds.left),
                (int) Math.floor(clampBounds.top),
                (int) Math.ceil(clampBounds.right),
                (int) Math.ceil(clampBounds.bottom));
        if (!out.intersect(clamp)) return new Rect();
        if (out.right <= out.left || out.bottom <= out.top) return new Rect();
        return out;
    }
}
