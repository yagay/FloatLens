package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Local screenshot detector for FV-style NonText rectangles.
 * It intentionally does not classify by app/widget semantics. Around the current selection point it
 * finds compact connected edge regions and returns their geometry as visual candidates. This lets a
 * launcher icon be represented separately from its BubbleTextView label when Accessibility exposes
 * only the combined cell.
 */
public final class VisualCandidateDetector {
    private VisualCandidateDetector() {}

    public static List<ScreenCandidate> detect(Context context, Bitmap bitmap, Rect displayBounds,
                                                float screenX, float screenY, Rect accessibilityHint) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (context == null || bitmap == null || bitmap.isRecycled() || displayBounds == null || displayBounds.isEmpty()) return out;

        final float sx = bitmap.getWidth() / (float)Math.max(1, displayBounds.width());
        final float sy = bitmap.getHeight() / (float)Math.max(1, displayBounds.height());
        final float density = context.getResources().getDisplayMetrics().density;

        Rect searchScreen = buildSearchRect(displayBounds, screenX, screenY, accessibilityHint, density);
        if (searchScreen.isEmpty()) return out;
        Rect search = mapToBitmap(searchScreen, displayBounds, bitmap, sx, sy);
        if (search.width() < 8 || search.height() < 8) return out;

        int step = Math.max(2, Math.round(Math.min(sx, sy) * 2.5f));
        int gw = Math.max(1, (search.width() + step - 1) / step);
        int gh = Math.max(1, (search.height() + step - 1) / step);
        if (gw * gh > 30000) {
            step = Math.max(step, 4);
            gw = Math.max(1, (search.width() + step - 1) / step);
            gh = Math.max(1, (search.height() + step - 1) / step);
        }

        int[] edge = new int[gw * gh];
        double sum = 0, sumSq = 0;
        for (int gy = 0; gy < gh; gy++) {
            int py = Math.min(bitmap.getHeight() - 2, search.top + gy * step);
            for (int gx = 0; gx < gw; gx++) {
                int px = Math.min(bitmap.getWidth() - 2, search.left + gx * step);
                int lum = luminance(bitmap.getPixel(px, py));
                int right = luminance(bitmap.getPixel(Math.min(bitmap.getWidth() - 1, px + step), py));
                int down = luminance(bitmap.getPixel(px, Math.min(bitmap.getHeight() - 1, py + step)));
                int diag = luminance(bitmap.getPixel(Math.min(bitmap.getWidth() - 1, px + step), Math.min(bitmap.getHeight() - 1, py + step)));
                int e = Math.max(Math.abs(lum - right), Math.max(Math.abs(lum - down), Math.abs(lum - diag)));
                edge[gy * gw + gx] = e;
                sum += e;
                sumSq += (double)e * e;
            }
        }

        int count = Math.max(1, gw * gh);
        double mean = sum / count;
        double variance = Math.max(0, sumSq / count - mean * mean);
        double std = Math.sqrt(variance);
        int threshold = (int)Math.max(20, Math.min(88, mean + std * 0.85));

        boolean[] active = new boolean[gw * gh];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int idx = gy * gw + gx;
                if (edge[idx] >= threshold || hasStrongNeighbor(edge, gw, gh, gx, gy, threshold)) active[idx] = true;
            }
        }

        boolean[] seen = new boolean[gw * gh];
        int pointerBx = Math.round((screenX - displayBounds.left) * sx);
        int pointerBy = Math.round((screenY - displayBounds.top) * sy);
        int maxDimPx = Math.round(180f * density * Math.max(sx, sy));
        int minDimPx = Math.max(6, Math.round(10f * density * Math.min(sx, sy)));

        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int start = gy * gw + gx;
                if (!active[start] || seen[start]) continue;

                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(start); seen[start] = true;
                int minGX = gx, maxGX = gx, minGY = gy, maxGY = gy;
                int cells = 0, strongCells = 0;
                while (!q.isEmpty() && cells < 6000) {
                    int cur = q.removeFirst();
                    int cx = cur % gw, cy = cur / gw;
                    cells++;
                    if (edge[cur] >= threshold) strongCells++;
                    minGX = Math.min(minGX, cx); maxGX = Math.max(maxGX, cx);
                    minGY = Math.min(minGY, cy); maxGY = Math.max(maxGY, cy);
                    for (int ny = Math.max(0, cy - 1); ny <= Math.min(gh - 1, cy + 1); ny++) {
                        for (int nx = Math.max(0, cx - 1); nx <= Math.min(gw - 1, cx + 1); nx++) {
                            int ni = ny * gw + nx;
                            if (!seen[ni] && active[ni]) { seen[ni] = true; q.addLast(ni); }
                        }
                    }
                }
                if (strongCells < 3) continue;

                Rect br = new Rect(
                        search.left + minGX * step,
                        search.top + minGY * step,
                        Math.min(bitmap.getWidth(), search.left + (maxGX + 1) * step),
                        Math.min(bitmap.getHeight(), search.top + (maxGY + 1) * step));
                int expand = Math.max(step * 2, Math.round(3f * density * Math.max(sx, sy)));
                br.inset(-expand, -expand);
                br.intersect(search);
                if (br.width() < minDimPx || br.height() < minDimPx) continue;
                if (br.width() > maxDimPx || br.height() > maxDimPx) continue;

                // The selected visual component must geometrically cover the pointer (small padding
                // compensates for a flat icon center whose edges surround, rather than cross, it).
                int pad = Math.max(step * 3, Math.round(8f * density * Math.max(sx, sy)));
                Rect pointerTest = new Rect(br); pointerTest.inset(-pad, -pad);
                if (!pointerTest.contains(pointerBx, pointerBy)) continue;

                Rect screenRect = mapToScreen(br, displayBounds, sx, sy);
                screenRect.intersect(displayBounds);
                if (screenRect.isEmpty()) continue;
                out.add(ScreenCandidate.visual(screenRect));
            }
        }
        return out;
    }

    private static Rect buildSearchRect(Rect display, float x, float y, Rect hint, float density) {
        Rect around = new Rect(Math.round(x - 110f * density), Math.round(y - 110f * density),
                Math.round(x + 110f * density), Math.round(y + 110f * density));
        around.intersect(display);
        if (hint == null || hint.isEmpty()) return around;
        Rect h = new Rect(hint);
        int pad = Math.round(8f * density);
        h.inset(-pad, -pad);
        h.intersect(display);
        if (h.isEmpty()) return around;
        // A huge/root accessibility node is not a useful visual search constraint.
        long ha = (long)h.width() * h.height();
        long da = (long)display.width() * display.height();
        if (da > 0 && ha > da * 70L / 100L) return around;
        Rect intersection = new Rect();
        if (intersection.setIntersect(around, h) && !intersection.isEmpty()) return intersection;
        return around;
    }

    private static Rect mapToBitmap(Rect screen, Rect display, Bitmap b, float sx, float sy) {
        int l = clamp(Math.round((screen.left - display.left) * sx), 0, b.getWidth() - 1);
        int t = clamp(Math.round((screen.top - display.top) * sy), 0, b.getHeight() - 1);
        int r = clamp(Math.round((screen.right - display.left) * sx), l + 1, b.getWidth());
        int bot = clamp(Math.round((screen.bottom - display.top) * sy), t + 1, b.getHeight());
        return new Rect(l, t, r, bot);
    }

    private static Rect mapToScreen(Rect bitmapRect, Rect display, float sx, float sy) {
        return new Rect(
                display.left + Math.round(bitmapRect.left / Math.max(0.0001f, sx)),
                display.top + Math.round(bitmapRect.top / Math.max(0.0001f, sy)),
                display.left + Math.round(bitmapRect.right / Math.max(0.0001f, sx)),
                display.top + Math.round(bitmapRect.bottom / Math.max(0.0001f, sy)));
    }

    private static boolean hasStrongNeighbor(int[] edge, int gw, int gh, int x, int y, int threshold) {
        int softer = Math.max(12, threshold * 3 / 4);
        if (edge[y * gw + x] >= softer) return true;
        for (int ny = Math.max(0, y - 1); ny <= Math.min(gh - 1, y + 1); ny++)
            for (int nx = Math.max(0, x - 1); nx <= Math.min(gw - 1, x + 1); nx++)
                if (edge[ny * gw + nx] >= threshold) return true;
        return false;
    }

    private static int luminance(int color) {
        return (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) >> 8;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
