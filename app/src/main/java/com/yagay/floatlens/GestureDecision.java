package com.yagay.floatlens;

/** Result of gesture-layer classification, before action binding. */
public final class GestureDecision {
    public static final GestureDecision NONE = new GestureDecision(-1, false, 0f, 0f);

    private final int code;
    private final boolean longTier;
    private final float extentX;
    private final float extentY;

    public GestureDecision(int code, boolean longTier, float extentX, float extentY) {
        this.code = code;
        this.longTier = longTier;
        this.extentX = extentX;
        this.extentY = extentY;
    }

    public int code() { return code; }
    public boolean longTier() { return longTier; }
    public float extentX() { return extentX; }
    public float extentY() { return extentY; }
    public boolean isNone() { return code < 0; }

    @Override public String toString() {
        return "GestureDecision{code=" + code + ",label=" + (code < 0 ? "none" : GestureCode.label(code)) +
                ",longTier=" + longTier + ",extentX=" + Math.round(extentX) + ",extentY=" + Math.round(extentY) + "}";
    }
}
