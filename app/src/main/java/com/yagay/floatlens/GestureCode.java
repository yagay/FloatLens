package com.yagay.floatlens;

/**
 * Gesture-layer codes separated from user-facing actions.
 * Values mirror codes confirmed from fooView 1.6.4 runtime traces, but the
 * semantics here are a clean-room FloatLens model rather than copied code.
 */
public final class GestureCode {
    public static final int CIRCLE_FINISH = 0;
    public static final int SIDE_SHORT = 1;
    public static final int SIDE_LONG = 2;
    public static final int UP = 4;
    public static final int AI_SCREEN = 6;
    public static final int TAP = 9;
    public static final int DOWN = 10;
    public static final int ENTER_CIRCLE = 16;
    public static final int RECOGNIZE = 30;

    public static String label(int code) {
        return switch (code) {
            case CIRCLE_FINISH -> "circle_finish";
            case SIDE_SHORT -> "side_short";
            case SIDE_LONG -> "side_long";
            case UP -> "up";
            case AI_SCREEN -> "ai_screen";
            case TAP -> "tap";
            case DOWN -> "down";
            case ENTER_CIRCLE -> "enter_circle";
            case RECOGNIZE -> "recognize";
            default -> "unknown_" + code;
        };
    }

    private GestureCode() {}
}
