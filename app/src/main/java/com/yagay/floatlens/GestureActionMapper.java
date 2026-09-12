package com.yagay.floatlens;

/** Maps FloatLens gesture-layer decisions to configurable action-layer IDs. */
public final class GestureActionMapper {
    public static String actionFor(FloatSettings fs, GestureDecision decision) {
        if (decision == null || decision.isNone()) return ActionId.NONE;
        return actionFor(fs, decision.code(), decision.longTier());
    }

    public static String actionFor(FloatSettings fs, int code) {
        return actionFor(fs, code, false);
    }

    public static String actionFor(FloatSettings fs, int code, boolean longTier) {
        return switch (code) {
            case GestureCode.TAP -> fs.clickScreenUnderIcon()
                    ? ActionId.CLICK_UNDER
                    : configured(fs, FloatSettings.K_ACTION_CLICK);
            case GestureCode.UP -> configured(fs, FloatSettings.K_ACTION_UP);
            case GestureCode.DOWN -> configured(fs,
                    longTier ? FloatSettings.K_ACTION_DOWN_LONG : FloatSettings.K_ACTION_DOWN_SHORT);
            case GestureCode.SIDE_SHORT -> configured(fs, FloatSettings.K_ACTION_SIDE_SHORT);
            case GestureCode.SIDE_LONG -> configured(fs, FloatSettings.K_ACTION_SIDE_LONG);
            case GestureCode.ENTER_CIRCLE -> configured(fs, FloatSettings.K_ACTION_LONG);
            case GestureCode.RECOGNIZE -> configured(fs, FloatSettings.K_ACTION_RECOGNIZE);
            case GestureCode.AI_SCREEN -> ActionId.AI_SCREEN;
            default -> ActionId.NONE;
        };
    }

    private static String configured(FloatSettings fs, String key) {
        return fs.action(key, ActionRegistry.defaultForPreference(key));
    }

    private GestureActionMapper() {}
}
