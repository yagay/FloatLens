package com.yagay.floatlens;

/** Pure view-space -> bitmap-space math used only by SelectionCropper. */
final class CropMath {
    static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    static Bounds viewRectFloorCeil(float left, float top, float right, float bottom,
                                    int sourceWidth, int sourceHeight,
                                    int viewWidth, int viewHeight) {
        requireDimensions(sourceWidth, sourceHeight, viewWidth, viewHeight);
        float sx = sourceWidth / (float) viewWidth;
        float sy = sourceHeight / (float) viewHeight;
        int l = clamp((int) Math.floor(left * sx), 0, sourceWidth - 1);
        int t = clamp((int) Math.floor(top * sy), 0, sourceHeight - 1);
        int r = clamp((int) Math.ceil(right * sx), l + 1, sourceWidth);
        int b = clamp((int) Math.ceil(bottom * sy), t + 1, sourceHeight);
        return new Bounds(l, t, r, b);
    }

    static Bounds viewRectRound(float left, float top, float right, float bottom,
                                int sourceWidth, int sourceHeight,
                                int viewWidth, int viewHeight) {
        requireDimensions(sourceWidth, sourceHeight, viewWidth, viewHeight);
        float sx = sourceWidth / (float) viewWidth;
        float sy = sourceHeight / (float) viewHeight;
        int l = clamp(Math.round(left * sx), 0, sourceWidth - 1);
        int t = clamp(Math.round(top * sy), 0, sourceHeight - 1);
        int r = clamp(Math.round(right * sx), l + 1, sourceWidth);
        int b = clamp(Math.round(bottom * sy), t + 1, sourceHeight);
        return new Bounds(l, t, r, b);
    }

    private static void requireDimensions(int sourceWidth, int sourceHeight,
                                          int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private CropMath() {}
}
