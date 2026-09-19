package com.yagay.floatlens;

/** Pure routing policy for SharedPreferences changes observed by FloatService. */
final class FloatPreferenceImpact {
    enum Impact {
        /** Resize/repaint/reposition icon windows and handles. */
        APPEARANCE_LAYOUT,
        /** Re-evaluate app/fullscreen/lock/IME/manual-wake visibility only. */
        VISIBILITY,
        /** Refresh FloatIconView's touch/renderer settings without WindowManager updates. */
        ICON_SETTINGS,
        /** FloatService only needs its latest FloatSettings snapshot. */
        SERVICE_SETTINGS,
        /** Service-generated persistence keys: never react to our own position save. */
        IGNORE
    }

    static Impact classify(String key) {
        if (key == null || key.isBlank()) return Impact.SERVICE_SETTINGS;
        return switch (key) {
            case FloatSettings.K_ALPHA,
                    FloatSettings.K_SIZE,
                    FloatSettings.K_SHOW_PERCENT,
                    FloatSettings.K_BOTH_SIDE,
                    FloatSettings.K_STYLE,
                    FloatSettings.K_CUSTOM_ICON,
                    FloatSettings.K_SLIDE_PICS,
                    FloatSettings.K_SLIDE_INTERVAL,
                    FloatSettings.K_GRAVITY,
                    FloatSettings.K_GRAVITY_LAND -> Impact.APPEARANCE_LAYOUT;

            case FloatSettings.K_SHOW_ON_LOCK,
                    FloatSettings.K_HIDE_MAIN_SWIPE,
                    FloatSettings.K_GLOBAL_DEFAULT_HIDE,
                    FloatSettings.K_HIDE_ICON_NO_NOTIFY,
                    FloatSettings.K_HIDE_FULLSCREEN,
                    FloatSettings.K_HIDE_PACKAGES,
                    FloatSettings.K_IME_AVOID -> Impact.VISIBILITY;

            case FloatSettings.K_LONG_PRESS,
                    FloatSettings.K_DOUBLE_TAP,
                    FloatSettings.K_TAP_MAX_MS,
                    FloatSettings.K_DOWN_SHORT_DISTANCE,
                    FloatSettings.K_SIDE_SHORT_DISTANCE,
                    FloatSettings.K_GESTURE_START_DISTANCE,
                    FloatSettings.K_VERTICAL_BIAS,
                    FloatSettings.K_LONG_PRESS_DRAG,
                    FloatSettings.K_VIBRATE -> Impact.ICON_SETTINGS;

            case FloatSettings.K_POSITION_SCHEMA,
                    FloatSettings.K_POSITION_SIDE,
                    FloatSettings.K_POSITION_Y_BP,
                    FloatSettings.K_POS_X_PORTRAIT,
                    FloatSettings.K_POS_Y_PORTRAIT,
                    FloatSettings.K_POS_X_LANDSCAPE,
                    FloatSettings.K_POS_Y_LANDSCAPE,
                    FloatSettings.K_POS_X,
                    FloatSettings.K_POS_Y -> Impact.IGNORE;

            default -> Impact.SERVICE_SETTINGS;
        };
    }

    private FloatPreferenceImpact() {}
}
