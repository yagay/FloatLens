package com.yagay.floatlens;

/** Pure coordinate mapping used by screenshot and selection crop paths. */
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

    static Bounds screenRectRound(int left, int top, int right, int bottom,
                                  int displayLeft, int displayTop,
                                  int displayWidth, int displayHeight,
                                  int bitmapWidth, int bitmapHeight) {
        requireDimensions(bitmapWidth, bitmapHeight, displayWidth, displayHeight);
        float sx = bitmapWidth / (float) displayWidth;
        float sy = bitmapHeight / (float) displayHeight;
        int l = clamp(Math.round((left - displayLeft) * sx), 0, bitmapWidth - 1);
        int t = clamp(Math.round((top - displayTop) * sy), 0, bitmapHeight - 1);
        int r = clamp(Math.round((right - displayLeft) * sx), l + 1, bitmapWidth);
        int b = clamp(Math.round((bottom - displayTop) * sy), t + 1, bitmapHeight);
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
