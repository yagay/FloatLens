package com.yagay.floatlens;

import java.util.HashMap;
import java.util.Map;

/** Explicit routing policy for every public FloatSettings preference key observed by FloatService. */
final class FloatPreferenceImpact {
    enum Impact {
        /** Resize/repaint/reposition icon windows and handles. */
        APPEARANCE_LAYOUT,
        /** Re-evaluate app/fullscreen/lock/IME/manual-wake visibility only. */
        VISIBILITY,
        /** Refresh FloatIconView's touch/renderer settings without WindowManager updates. */
        ICON_SETTINGS,
        /** Refresh the live Circle border without rebuilding the floating icon. */
        CIRCLE_OVERLAY,
        /** FloatService only needs its latest FloatSettings snapshot. */
        SERVICE_SETTINGS,
        /** Internal persistence/state keys that must never trigger service refresh work. */
        IGNORE
    }

    private static final Map<String, Impact> IMPACTS = new HashMap<>();

    static {
        register(Impact.APPEARANCE_LAYOUT,
                FloatSettings.K_ALPHA,
                FloatSettings.K_SIZE,
                FloatSettings.K_SHOW_PERCENT,
                FloatSettings.K_BOTH_SIDE,
                FloatSettings.K_STYLE,
                FloatSettings.K_CUSTOM_ICON,
                FloatSettings.K_SLIDE_PICS,
                FloatSettings.K_SLIDE_INTERVAL,
                FloatSettings.K_GRAVITY,
                FloatSettings.K_GRAVITY_LAND);

        register(Impact.VISIBILITY,
                FloatSettings.K_SHOW_ON_LOCK,
                FloatSettings.K_HIDE_MAIN_SWIPE,
                FloatSettings.K_GLOBAL_DEFAULT_HIDE,
                FloatSettings.K_HIDE_ICON_NO_NOTIFY,
                FloatSettings.K_HIDE_FULLSCREEN,
                FloatSettings.K_HIDE_PACKAGES,
                FloatSettings.K_IME_AVOID);

        register(Impact.ICON_SETTINGS,
                FloatSettings.K_LONG_PRESS,
                FloatSettings.K_DOUBLE_TAP,
                FloatSettings.K_TAP_MAX_MS,
                FloatSettings.K_DOWN_SHORT_DISTANCE,
                FloatSettings.K_SIDE_SHORT_DISTANCE,
                FloatSettings.K_GESTURE_START_DISTANCE,
                FloatSettings.K_VERTICAL_BIAS,
                FloatSettings.K_LONG_PRESS_DRAG,
                FloatSettings.K_VIBRATE);

        register(Impact.CIRCLE_OVERLAY,
                FloatSettings.K_CIRCLE_BORDER_ENABLED,
                FloatSettings.K_CIRCLE_BORDER_COLOR,
                FloatSettings.K_CIRCLE_BORDER_WIDTH_DP);

        register(Impact.SERVICE_SETTINGS,
                FloatSettings.K_TRACK,
                FloatSettings.K_KEEP_IN_SCREENSHOT,
                FloatSettings.K_KEEP_STATUS_BAR,
                FloatSettings.K_KEEP_NAVIGATION_BAR,
                FloatSettings.K_ACCESSIBILITY_SCREENSHOT,
                FloatSettings.K_CIRCLE_ENGINE,
                FloatSettings.K_CIRCLE_HYBRID_OCR,
                FloatSettings.K_CIRCLE_FULL_OCR_ENGINE,
                FloatSettings.K_CIRCLE_CORRECTION_ENGINE,
                FloatSettings.K_CLICK_UNDER,
                FloatSettings.K_LINE_ALPHA,
                FloatSettings.K_LINE_WIDTH,
                FloatSettings.K_LINE_STYLE,
                FloatSettings.K_LINE_COLORS,
                FloatSettings.K_LINE_GRADIENT,
                FloatSettings.K_OCR_SHOW_TEXT,
                FloatSettings.K_OCR_SHOW_IMAGE,
                FloatSettings.K_OCR_COLLAPSE,
                FloatSettings.K_OCR_TYPE,
                FloatSettings.K_OCR_ENGINE,
                FloatSettings.K_SNAP,
                FloatSettings.K_ROOT_SCREENSHOT,
                FloatSettings.K_QUICK_MOVE,
                FloatSettings.K_DIAGNOSTIC,
                FloatSettings.K_ENHANCED_MODE,
                FloatSettings.K_ROOT_ENABLED,
                FloatSettings.K_LSPOSED_ENABLED,
                FloatSettings.K_LSPOSED_SECURE_SCREENSHOT,
                FloatSettings.K_PRIVILEGE_FALLBACK,
                FloatSettings.K_ACTION_CLICK,
                FloatSettings.K_ACTION_DOUBLE,
                FloatSettings.K_ACTION_LONG,
                FloatSettings.K_ACTION_RECOGNIZE,
                FloatSettings.K_ACTION_UP,
                FloatSettings.K_ACTION_DOWN_SHORT,
                FloatSettings.K_ACTION_DOWN_LONG,
                FloatSettings.K_ACTION_SIDE_SHORT,
                FloatSettings.K_ACTION_SIDE_LONG);

        register(Impact.IGNORE,
                FloatSettings.K_GOOGLE_CTS_ACTIVE_SESSION,
                FloatSettings.K_GOOGLE_CTS_ACTIVE_UNTIL,
                FloatSettings.K_ROOT_LAST_GRANTED,
                FloatSettings.K_ROOT_LAST_CHECK,
                FloatSettings.K_ROOT_LAST_DETAIL,
                FloatSettings.K_MIGRATE_LONG_PRESS_CONFIG_V1,
                FloatSettings.K_POSITION_SCHEMA,
                FloatSettings.K_POSITION_SIDE,
                FloatSettings.K_POSITION_Y_BP,
                FloatSettings.K_POS_X_PORTRAIT,
                FloatSettings.K_POS_Y_PORTRAIT,
                FloatSettings.K_POS_X_LANDSCAPE,
                FloatSettings.K_POS_Y_LANDSCAPE,
                FloatSettings.K_POS_X,
                FloatSettings.K_POS_Y);
    }

    static Impact classify(String key) {
        if (key == null || key.isBlank()) return Impact.SERVICE_SETTINGS;
        return IMPACTS.getOrDefault(key, Impact.SERVICE_SETTINGS);
    }

    static boolean isExplicitlyClassified(String key) {
        return key != null && IMPACTS.containsKey(key);
    }

    private static void register(Impact impact, String... keys) {
        if (impact == null || keys == null) return;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            Impact previous = IMPACTS.put(key, impact);
            if (previous != null && previous != impact) {
                throw new IllegalStateException("Duplicate FloatSettings impact key: " + key);
            }
        }
    }

    private FloatPreferenceImpact() {}
}
