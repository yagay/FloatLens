package com.yagay.floatlens;

/** FloatLens gesture classifier for the FL touch state machine. */
public final class GestureClassifier {
    public static GestureDecision classify(GestureSession session, FloatSettings fs, float density) {
        return classify(session,
                fs.gestureStartDistance() * density,
                fs.verticalBias(),
                fs.downShortDistancePx(density),
                fs.sideShortDistancePx(density));
    }

    /** Pure classification core kept separate so FV gesture semantics can be regression-tested. */
    static GestureDecision classify(GestureSession session,
                                    float gestureStartPx,
                                    float verticalBias,
                                    float downShortPx,
                                    float sideShortPx) {
        if (session == null || session.points.isEmpty()) return GestureDecision.NONE;
        float minX = session.downX, maxX = session.downX;
        float minY = session.downY, maxY = session.downY;
        for (GesturePointSample p : session.points) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        float dx = session.dx(), dy = session.dy();
        float ax = Math.max(Math.abs(dx), maxX - minX);
        float ay = Math.max(Math.abs(dy), maxY - minY);
        if (Math.hypot(dx, dy) < gestureStartPx && Math.max(ax, ay) < gestureStartPx) {
            return GestureDecision.NONE;
        }
        if (ay > ax * verticalBias) {
            if (dy < 0) return new GestureDecision(GestureCode.UP, false, ax, ay);
            boolean longTier = ay >= downShortPx;
            return new GestureDecision(GestureCode.DOWN, longTier, ax, ay);
        }
        boolean longTier = ax >= sideShortPx;
        return new GestureDecision(longTier ? GestureCode.SIDE_LONG : GestureCode.SIDE_SHORT,
                longTier, ax, ay);
    }

    private GestureClassifier() {}
}
