package com.yagay.floatlens;

/** Confirmed fooView FV runtime gesture/mode codes, kept separate from FloatLens action IDs. */
public final class FvGestureCode {
    public static final int CIRCLE_FINISH = 0;
    public static final int SIDE_SHORT = 1;
    public static final int SIDE_LONG = 2;
    public static final int UP = 4;
    public static final int TAP = 9;
    public static final int DOWN = 10;
    public static final int ENTER_CIRCLE = 16;
    public static final int RECOGNIZE = 30;

    public static String label(int code) {
        return switch (code) {
            case CIRCLE_FINISH -> "CIRCLE_FINISH";
            case SIDE_SHORT -> "SIDE_SHORT";
            case SIDE_LONG -> "SIDE_LONG";
            case UP -> "UP";
            case TAP -> "TAP";
            case DOWN -> "DOWN";
            case ENTER_CIRCLE -> "ENTER_CIRCLE";
            case RECOGNIZE -> "RECOGNIZE";
            default -> "UNKNOWN_" + code;
        };
    }

    private FvGestureCode() {}
}
