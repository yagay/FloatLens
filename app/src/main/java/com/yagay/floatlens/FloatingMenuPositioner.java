package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

/** Single owner for floating action-menu placement and screen-edge clamping. */
final class FloatingMenuPositioner {
    static int[] aroundAnchor(Context c, Rect usable, Rect anchor,
                              int menuWidth, int menuHeight, boolean centerWhenMissing) {
        int margin = UiTokens.dp(c, 8);
        int gap = UiTokens.dp(c, 8);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - menuWidth);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - menuHeight);

        if (anchor == null || anchor.isEmpty()) {
            int x = ScreenGeometry.clamp(usable.centerX() - menuWidth / 2, minX, maxX);
            int fallbackY = centerWhenMissing
                    ? usable.centerY() - menuHeight / 2
                    : usable.top + UiTokens.dp(c, 52);
            return new int[]{x, ScreenGeometry.clamp(fallbackY, minY, maxY)};
        }

        int x = ScreenGeometry.clamp(anchor.centerX() - menuWidth / 2, minX, maxX);
        int above = anchor.top - gap - menuHeight;
        int below = anchor.bottom + gap;
        int y;
        if (above >= minY) y = above;
        else if (below <= maxY) y = below;
        else {
            int roomAbove = Math.max(0, anchor.top - minY);
            int roomBelow = Math.max(0, usable.bottom - margin - anchor.bottom);
            y = roomBelow >= roomAbove
                    ? ScreenGeometry.clamp(below, minY, maxY)
                    : ScreenGeometry.clamp(above, minY, maxY);
        }
        return new int[]{x, y};
    }

    static int[] lockedRow(Context c, Rect usable, int centerX, int topY,
                           int menuWidth, int menuHeight) {
        int margin = UiTokens.dp(c, 8);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - menuWidth);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - menuHeight);
        return new int[]{
                ScreenGeometry.clamp(centerX - menuWidth / 2, minX, maxX),
                ScreenGeometry.clamp(topY, minY, maxY)};
    }

    private FloatingMenuPositioner() {}
}
