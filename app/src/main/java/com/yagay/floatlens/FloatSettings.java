package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Preference model for FloatLens' fooView-style floating icon. */
public final class FloatSettings {
    public static final String PREF = "floatlens_float";

    public static final String K_ALPHA = "icon_alpha";
    public static final String K_SIZE = "float_icon_real_size";
    public static final String K_SHOW_PERCENT = "float_icon_show_percentage";
    public static final String K_LONG_PRESS = "icon_long_press_time";
    public static final String K_DOUBLE_TAP = "icon_db_click_detect_time";
    public static final String K_DOWN_SHORT_DISTANCE = "down_swipe_short_distance_2";
    public static final String K_SIDE_SHORT_DISTANCE = "side_swipe_short_distance_2";
    public static final String K_TRACK = "showGestureTracking";
    public static final String K_BOTH_SIDE = "float_on_both_side";
    public static final String K_SHOW_ON_LOCK = "icon_show_lock_screen";
    public static final String K_KEEP_IN_SCREENSHOT = "screen_capture_keep_icon";
    public static final String K_KEEP_STATUS_BAR = "screen_capture_keep_noti_bar";
    public static final String K_ACCESSIBILITY_SCREENSHOT = "screen_capture_accessibility";
    public static final String K_VIEW_CAPTURE_DWELL = "view_capture_dwell_ms";
    public static final String K_STYLE = "float_icon_style";
    public static final String K_GRAVITY = "float_gravity";
    public static final String K_GRAVITY_LAND = "float_gravity_land";
    public static final String K_HIDE_MAIN_SWIPE = "hide_main_icon_swipe_gesture";
    public static final String K_GLOBAL_DEFAULT_HIDE = "global_app_default_hide";
    public static final String K_HIDE_ICON_NO_NOTIFY = "hide_icon_no_notify";
    public static final String K_HIDE_FULLSCREEN = "hide_icon_when_full_screen";
    public static final String K_CLICK_UNDER = "action_click_screen_under_icon";
    public static final String K_LINE_ALPHA = "float_line_alpha";
    public static final String K_LINE_WIDTH = "float_line_width_2";
    public static final String K_LINE_STYLE = "float_line_style";
    public static final String K_LINE_COLORS = "float_line_colors";
    public static final String K_LINE_GRADIENT = "float_line_color_gradient";
    public static final String K_OCR_SHOW_TEXT = "ocr_result_show_text";
    public static final String K_OCR_SHOW_IMAGE = "ocr_result_show_image";
    public static final String K_OCR_COLLAPSE = "ocr_result_show_text_collapse";
    public static final String K_OCR_TYPE = "ocr_type";
    public static final String K_CUSTOM_ICON = "float_icon_custom_pic";
    public static final String K_SLIDE_PICS = "float_icon_slide_pics";
    public static final String K_SLIDE_INTERVAL = "float_icon_slide_interval";

    public static final String K_LONG_PRESS_DRAG = "isLongPressDragEnabled";
    public static final String K_SNAP = "edge_snap_enabled";
    public static final String K_VIBRATE = "vibration_fb";
    public static final String K_ROOT_SCREENSHOT = "use_root_for_screenshots";
    public static final String K_GESTURE_START_DISTANCE = "gesture_start_distance";
    public static final String K_VERTICAL_BIAS = "gesture_vertical_bias_x100";
    public static final String K_TAP_MAX_MS = "tap_max_duration_ms";
    public static final String K_HIDE_PACKAGES = "hide_icon_packages_csv";
    public static final String K_IME_AVOID = "ime_avoid_icon";
    public static final String K_QUICK_MOVE = "quickMoveIcon";
    public static final String K_DIAGNOSTIC = "fv_diagnostic_logging";
    private static final String K_MIGRATE_LONG_PRESS_CONFIG_V1 = "migrate_long_press_config_v1";

    public static final String K_POS_X_PORTRAIT = "float_pos_x_portrait";
    public static final String K_POS_Y_PORTRAIT = "float_pos_y_portrait";
    public static final String K_POS_X_LANDSCAPE = "float_pos_x_landscape";
    public static final String K_POS_Y_LANDSCAPE = "float_pos_y_landscape";
    public static final String K_POS_X = "float_pos_x";
    public static final String K_POS_Y = "float_pos_y";

    public static final String K_ACTION_CLICK = "action_click";
    public static final String K_ACTION_DOUBLE = "action_db_click";
    public static final String K_ACTION_LONG = "action_long_press";
    public static final String K_ACTION_RECOGNIZE = "action_recognize";
    public static final String K_ACTION_UP = "gesture_up";
    public static final String K_ACTION_DOWN_SHORT = "gesture_down_short";
    public static final String K_ACTION_DOWN_LONG = "gesture_down_long";
    public static final String K_ACTION_SIDE_SHORT = "gesture_side_short";
    public static final String K_ACTION_SIDE_LONG = "gesture_side_long";

    private final Context context;
    private final SharedPreferences p;

    public FloatSettings(Context c) {
        context = c.getApplicationContext();
        p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        migrateOnce();
    }

    private void migrateOnce() {
        if (!p.contains(K_SHOW_PERCENT) && p.contains("float_edge_hide_percent")) {
            int hidden = clamp(p.getInt("float_edge_hide_percent", 28), 0, 90);
            p.edit().putInt(K_SHOW_PERCENT, 100 - hidden).apply();
        }
        if (!p.contains(K_KEEP_IN_SCREENSHOT) && p.contains("setting_screenshot_keep_float_icon")) {
            p.edit().putBoolean(K_KEEP_IN_SCREENSHOT, p.getBoolean("setting_screenshot_keep_float_icon", false)).apply();
        }
        Object old = p.getAll().get(K_HIDE_FULLSCREEN);
        if (old instanceof Boolean b) p.edit().putInt(K_HIDE_FULLSCREEN, b ? 2 : 0).apply();
        if (!p.getBoolean(K_MIGRATE_LONG_PRESS_CONFIG_V1, false)) {
            SharedPreferences.Editor e = p.edit();
            if (ActionId.OCR.equals(p.getString(K_ACTION_LONG, null))) e.remove(K_ACTION_LONG);
            e.putBoolean(K_MIGRATE_LONG_PRESS_CONFIG_V1, true).apply();
        }
    }

    public float alpha() { return clamp(p.getInt(K_ALPHA, 62), 10, 100) / 100f; }
    public int sizeDp() { return clamp(p.getInt(K_SIZE, 48), 24, 96); }
    public int showPercentage() { return clamp(p.getInt(K_SHOW_PERCENT, 72), 10, 100); }
    public int hiddenPercent() { return 100 - showPercentage(); }
    public int longPressMs() { return clamp(p.getInt(K_LONG_PRESS, 300), 100, 1500); }
    /** FV 1.6.4 onCreate reads icon_db_click_detect_time with a 200 ms default. */
    public int doubleTapMs() { return clamp(p.getInt(K_DOUBLE_TAP, 200), 120, 800); }
    public int tapMaxMs() { return clamp(p.getInt(K_TAP_MAX_MS, 150), 80, 400); }
    public int viewCaptureDwellMs() { return clamp(p.getInt(K_VIEW_CAPTURE_DWELL, 500), 200, 2000); }
    public int downShortDistance() { return clamp(p.getInt(K_DOWN_SHORT_DISTANCE, 200), 30, 900); }
    public int sideShortDistance() { return clamp(p.getInt(K_SIDE_SHORT_DISTANCE, 320), 30, 1200); }
    public int gestureStartDistance() { return clamp(p.getInt(K_GESTURE_START_DISTANCE, 30), 5, 100); }
    public float verticalBias() { return clamp(p.getInt(K_VERTICAL_BIAS, 120), 100, 300) / 100f; }
    public int style() { return clamp(p.getInt(K_STYLE, 0), 0, 4); }

    public float downShortDistancePx(float density) { return downShortDistance() * density; }
    public float sideShortDistancePx(float density) { return sideShortDistance() * density; }

    public boolean snap() { return p.getBoolean(K_SNAP, true); }
    public boolean vibrate() { return p.getBoolean(K_VIBRATE, true); }
    public boolean track() { return p.getBoolean(K_TRACK, false); }
    public boolean bothSide() { return p.getBoolean(K_BOTH_SIDE, false); }
    public boolean showOnLock() { return p.getBoolean(K_SHOW_ON_LOCK, true); }
    public boolean keepInScreenshot() { return p.getBoolean(K_KEEP_IN_SCREENSHOT, false); }
    public boolean keepStatusBarInScreenshot() { return p.getBoolean(K_KEEP_STATUS_BAR, true); }
    public boolean accessibilityScreenshot() { return p.getBoolean(K_ACCESSIBILITY_SCREENSHOT, true); }
    public boolean rootScreenshot() { return p.getBoolean(K_ROOT_SCREENSHOT, false); }
    public boolean longPressDragEnabled() { return p.getBoolean(K_LONG_PRESS_DRAG, true); }
    public boolean quickMoveEnabled() { return p.getBoolean(K_QUICK_MOVE, true); }
    public boolean diagnosticLogging() { return p.getBoolean(K_DIAGNOSTIC, false); }
    public boolean imeAvoid() { return p.getBoolean(K_IME_AVOID, true); }
    public boolean defaultHideByApp() { return p.getBoolean(K_GLOBAL_DEFAULT_HIDE, false); }
    public boolean hideWithoutNotify() { return p.getBoolean(K_HIDE_ICON_NO_NOTIFY, false); }
    public int fullscreenHideMode() {
        Object raw = p.getAll().get(K_HIDE_FULLSCREEN);
        if (raw instanceof Number n) return clamp(n.intValue(), 0, 3);
        if (raw instanceof Boolean b) return b ? 2 : 0;
        return 0;
    }
    public boolean hideWhenFullscreen() { return fullscreenHideMode() != 0; }
    public boolean clickScreenUnderIcon() { return p.getBoolean(K_CLICK_UNDER, false); }
    public int lineAlpha() { return clamp(p.getInt(K_LINE_ALPHA, 80), 10, 100); }
    public int lineWidthDp() { return clamp(p.getInt(K_LINE_WIDTH, 6), 1, 24); }
    public int lineStyle() { return clamp(p.getInt(K_LINE_STYLE, 0), 0, 2); }
    public String lineColors() { return p.getString(K_LINE_COLORS, "#FFFFFF"); }
    public boolean lineGradient() { return p.getBoolean(K_LINE_GRADIENT, false); }
    public boolean ocrShowText() { return p.getBoolean(K_OCR_SHOW_TEXT, true); }
    public boolean ocrShowImage() { return p.getBoolean(K_OCR_SHOW_IMAGE, true); }
    public boolean ocrCollapse() { return p.getBoolean(K_OCR_COLLAPSE, false); }
    public int ocrType() { return clamp(p.getInt(K_OCR_TYPE, 0), 0, 1); }
    public String customIconUri() { return p.getString(K_CUSTOM_ICON, ""); }
    public String slidePics() { return p.getString(K_SLIDE_PICS, ""); }
    public int slideIntervalMs() { return clamp(p.getInt(K_SLIDE_INTERVAL, 3000), 500, 60000); }
    public String action(String key, String def) { return p.getString(key, def); }
    public SharedPreferences prefs() { return p; }

    public Set<String> hiddenPackages() {
        String raw = p.getString(K_HIDE_PACKAGES, "");
        Set<String> out = new HashSet<>();
        if (raw == null || raw.isBlank()) return out;
        Arrays.stream(raw.split("[,\\n; ]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }

    public boolean shouldHideForPackage(String pkg) {
        if (pkg == null || pkg.isBlank() || pkg.equals(context.getPackageName())) return false;
        Set<String> set = hiddenPackages();
        boolean listed = set.contains(pkg);
        return defaultHideByApp() ? !listed : listed;
    }

    public boolean isLandscape() {
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    public String posXKey() { return isLandscape() ? K_POS_X_LANDSCAPE : K_POS_X_PORTRAIT; }
    public String posYKey() { return isLandscape() ? K_POS_Y_LANDSCAPE : K_POS_Y_PORTRAIT; }
    public String gravityKey() { return isLandscape() ? K_GRAVITY_LAND : K_GRAVITY; }
    public int savedSide(int def) { return p.getInt(gravityKey(), def); }
    public void saveSide(boolean left) { p.edit().putInt(gravityKey(), left ? 0 : 1).apply(); }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
