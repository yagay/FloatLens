package com.yagay.floatlens;

public final class CanonicalFramePolicy {
    public static boolean shouldReplace(
            int currentWidth, int currentHeight, int candidateWidth, int candidateHeight) {
        long currentArea = Math.max(0L, (long) currentWidth * currentHeight);
        long candidateArea = Math.max(0L, (long) candidateWidth * candidateHeight);
        return currentArea <= 0L || candidateArea > currentArea;
    }

    private CanonicalFramePolicy() {}
}
