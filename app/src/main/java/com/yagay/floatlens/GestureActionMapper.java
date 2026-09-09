package com.yagay.floatlens;

/** Maps FloatLens gesture-layer codes to configurable action-layer IDs. */
public final class GestureActionMapper {
    public static String actionFor(FloatSettings fs, int code) {
        return switch (code) {
            case GestureCode.TAP -> fs.clickScreenUnderIcon()
                    ? ActionId.CLICK_UNDER
                    : fs.action(FloatSettings.K_ACTION_CLICK, ActionId.NONE);
            case GestureCode.UP -> fs.action(FloatSettings.K_ACTION_UP, ActionId.RECENTS);
            case GestureCode.DOWN -> fs.action(FloatSettings.K_ACTION_DOWN_SHORT, ActionId.NOTIFICATIONS);
            case GestureCode.SIDE_SHORT -> fs.action(FloatSettings.K_ACTION_SIDE_SHORT, ActionId.BACK);
            case GestureCode.SIDE_LONG -> fs.action(FloatSettings.K_ACTION_SIDE_LONG, ActionId.NONE);
            case GestureCode.ENTER_CIRCLE -> fs.action(FloatSettings.K_ACTION_LONG, ActionId.OCR);
            case GestureCode.RECOGNIZE -> fs.action(FloatSettings.K_ACTION_RECOGNIZE, ActionId.OCR);
            case GestureCode.AI_SCREEN -> ActionId.AI_SCREEN;
            default -> ActionId.NONE;
        };
    }

    private GestureActionMapper() {}
}
