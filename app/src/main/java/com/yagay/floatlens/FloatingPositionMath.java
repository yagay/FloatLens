package com.yagay.floatlens;

/** Pure floating-position math; no Android state, persistence, or orientation assumptions. */
final class FloatingPositionMath {
    static int edgeX(int side, int screenWidth, int iconWidth) {
        int width = Math.max(0, screenWidth);
        int icon = Math.max(0, iconWidth);
        return side == 0 ? 0 : Math.max(0, width - icon);
    }

    static int yFromBasisPoints(int basisPoints, int availableHeight) {
        int available = Math.max(0, availableHeight);
        int bp = clamp(basisPoints, 0, 10000);
        return Math.round(available * (bp / 10000f));
    }

    static int basisPointsFromY(int y, int availableHeight, int fallback) {
        int available = Math.max(0, availableHeight);
        if (available <= 0) return clamp(fallback, 0, 10000);
        return clamp(Math.round(Math.max(0, y) * 10000f / available), 0, 10000);
    }

    static int hiddenPixels(int iconWidth, int hiddenPercent) {
        return Math.round(Math.max(0, iconWidth) * clamp(hiddenPercent, 0, 100) / 100f);
    }

    static int hiddenEdgeX(boolean left, int screenWidth, int iconWidth, int hiddenPercent) {
        int hidden = hiddenPixels(iconWidth, hiddenPercent);
        return left ? -hidden : Math.max(0, screenWidth - iconWidth) + hidden;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private FloatingPositionMath() {}
}
