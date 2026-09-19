package com.yagay.floatlens;

final class GoogleRegionConfirmPositioner {
    static final class Placement {
        final int left;
        final int top;
        Placement(int left, int top) { this.left = left; this.top = top; }
    }

    static Placement place(
            int selectionLeft, int selectionTop, int selectionRight, int selectionBottom,
            int usableLeft, int usableTop, int usableRight, int usableBottom,
            int buttonWidth, int buttonHeight, int margin) {
        int maxLeft = Math.max(usableLeft, usableRight - buttonWidth);
        int left = clamp(selectionRight - buttonWidth, usableLeft, maxLeft);
        int below = selectionBottom + margin;
        int above = selectionTop - margin - buttonHeight;
        int maxTop = Math.max(usableTop, usableBottom - buttonHeight);
        int top;
        if (below + buttonHeight <= usableBottom) top = below;
        else if (above >= usableTop) top = above;
        else top = clamp(selectionBottom - buttonHeight, usableTop, maxTop);
        return new Placement(left, clamp(top, usableTop, maxTop));
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private GoogleRegionConfirmPositioner() {}
}
