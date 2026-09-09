package com.yagay.floatlens;

/** Clean-room gesture classifier derived from observed FV runtime behavior. */
public final class GestureClassifier {
    public static GestureDecision classify(GestureSession session, FloatSettings fs, float density) {
        if (session == null || session.points.isEmpty()) return GestureDecision.NONE;
        float minX=session.downX,maxX=session.downX,minY=session.downY,maxY=session.downY;
        for (GesturePointSample p:session.points) {
            minX=Math.min(minX,p.x()); maxX=Math.max(maxX,p.x());
            minY=Math.min(minY,p.y()); maxY=Math.max(maxY,p.y());
        }
        float dx=session.dx(),dy=session.dy();
        float ax=Math.max(Math.abs(dx),maxX-minX), ay=Math.max(Math.abs(dy),maxY-minY);
        float slop=fs.gestureStartDistance()*density;
        if (Math.hypot(dx,dy)<slop && Math.max(ax,ay)<slop) return GestureDecision.NONE;
        if (ay>ax*fs.verticalBias()) {
            if (dy<0) return new GestureDecision(GestureCode.UP,false,ax,ay);
            boolean longTier=ay>=fs.downShortDistancePx(density);
            return new GestureDecision(GestureCode.DOWN,longTier,ax,ay);
        }
        boolean longTier=ax>=fs.sideShortDistancePx(density);
        return new GestureDecision(longTier?GestureCode.SIDE_LONG:GestureCode.SIDE_SHORT,longTier,ax,ay);
    }
    private GestureClassifier(){}
}
