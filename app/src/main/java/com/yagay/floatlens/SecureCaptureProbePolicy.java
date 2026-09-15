package com.yagay.floatlens;

/** Pure color classifier used by the on-device FLAG_SECURE capture probe. */
final class SecureCaptureProbePolicy {
    static final int MARKER_RED = 37;
    static final int MARKER_GREEN = 199;
    static final int MARKER_BLUE = 83;
    static final int CHANNEL_TOLERANCE = 28;

    private SecureCaptureProbePolicy() {}

    static boolean isMarkerColor(int red, int green, int blue) {
        return Math.abs(red - MARKER_RED) <= CHANNEL_TOLERANCE
                && Math.abs(green - MARKER_GREEN) <= CHANNEL_TOLERANCE
                && Math.abs(blue - MARKER_BLUE) <= CHANNEL_TOLERANCE;
    }

    static boolean isSuccessful(int matchingSamples, int totalSamples) {
        if (totalSamples <= 0 || matchingSamples < 0) return false;
        return matchingSamples * 100 >= totalSamples * 60;
    }
}
