package com.yagay.floatlens;

/** Pure rectangle math for Circle ROI precedence, kept JVM-testable without android.graphics.Rect. */
final class CircleRoiOverridePolicy {
    static boolean materiallyCovered(int lineLeft, int lineTop, int lineRight, int lineBottom,
                                     int regionLeft, int regionTop, int regionRight, int regionBottom) {
        int lineWidth = Math.max(0, lineRight - lineLeft);
        int lineHeight = Math.max(0, lineBottom - lineTop);
        int regionWidth = Math.max(0, regionRight - regionLeft);
        int regionHeight = Math.max(0, regionBottom - regionTop);
        if (lineWidth == 0 || lineHeight == 0 || regionWidth == 0 || regionHeight == 0) return false;

        int centerX = lineLeft + lineWidth / 2;
        int centerY = lineTop + lineHeight / 2;
        if (centerX >= regionLeft && centerX < regionRight
                && centerY >= regionTop && centerY < regionBottom) return true;

        int overlapLeft = Math.max(lineLeft, regionLeft);
        int overlapTop = Math.max(lineTop, regionTop);
        int overlapRight = Math.min(lineRight, regionRight);
        int overlapBottom = Math.min(lineBottom, regionBottom);
        if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) return false;
        long overlap = (long) (overlapRight - overlapLeft) * (overlapBottom - overlapTop);
        long lineArea = Math.max(1L, (long) lineWidth * lineHeight);
        return overlap * 100L >= lineArea * 28L;
    }

    static boolean intersectsOrContains(int aLeft, int aTop, int aRight, int aBottom,
                                        int bLeft, int bTop, int bRight, int bBottom) {
        if (aRight <= aLeft || aBottom <= aTop || bRight <= bLeft || bBottom <= bTop) return false;
        return Math.max(aLeft, bLeft) < Math.min(aRight, bRight)
                && Math.max(aTop, bTop) < Math.min(aBottom, bBottom);
    }

    private CircleRoiOverridePolicy() {}
}
