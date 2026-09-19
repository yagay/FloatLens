package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Preference model and single normal access boundary for FloatLens settings. */
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
    public static final String K_KEEP_NAVIGATION_BAR = "screen_capture_keep_navigation_bar_v1";
    public static final String K_ACCESSIBILITY_SCREENSHOT = "screen_capture_accessibility";
    /** 0=FloatLens native circle, 1=Google Circle to Search (FloatLens-marked session). */
    public static final String K_CIRCLE_ENGINE = "circle_engine_v1";
    public static final String K_CIRCLE_BORDER_ENABLED = "circle_active_border_enabled_v1";
    public static final String K_CIRCLE_BORDER_COLOR = "circle_active_border_color_v1";
    public static final String K_CIRCLE_BORDER_WIDTH_DP = "circle_active_border_width_dp_v1";
    /** Legacy migration source for the earlier one-switch hybrid OCR experiment. */
    public static final String K_CIRCLE_HYBRID_OCR = "circle_hybrid_ocr_v1";
    /** 0=ML Kit, 1=PP Tiny, 2=PP Small, 3=PP Medium. */
    public static final String K_CIRCLE_FULL_OCR_ENGINE = "circle_full_ocr_engine_v1";
    /** 0=disabled, 1=PP Tiny, 2=PP Small, 3=PP Medium. */
    public static final String K_CIRCLE_CORRECTION_ENGINE = "circle_correction_engine_v1";
    public static final int DEFAULT_CIRCLE_BORDER_COLOR = 0xFF4285F4;
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
    public static final String K_OCR_ENGINE = "ocr_engine_mode_v2";
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
    public static final String K_DIAGNOSTIC = "diagnostic_logging";
    public static final String K_GOOGLE_CTS_ACTIVE_SESSION = "google_cts_active_session_v1";
    public static final String K_GOOGLE_CTS_ACTIVE_UNTIL = "google_cts_active_until_elapsed_v1";

    public static final String K_ENHANCED_MODE = "privilege_enhanced_mode_v1";
    public static final String K_ROOT_ENABLED = "privilege_root_enabled_v1";
    public static final String K_LSPOSED_ENABLED = "privilege_lsposed_enabled_v1";
    public static final String K_LSPOSED_SECURE_SCREENSHOT = "lsposed_secure_screenshot_v1";
    public static final String K_PRIVILEGE_FALLBACK = "privilege_fallback_normal_v1";
    public static final String K_ROOT_LAST_GRANTED = "privilege_root_last_granted_v1";
    public static final String K_ROOT_LAST_CHECK = "privilege_root_last_check_ms_v1";
    public static final String K_ROOT_LAST_DETAIL = "privilege_root_last_detail_v1";

    static final String K_MIGRATE_LONG_PRESS_CONFIG_V1 = "migrate_long_press_config_v1";
    private static final String K_CIRCLE_CANCEL_X_BP = "circle_cancel_x_bp_v1";
    private static final String K_CIRCLE_CANCEL_Y_BP = "circle_cancel_y_bp_v1";

    static final int POSITION_SCHEMA_VERSION = 2;
    public static final String K_POSITION_SCHEMA = "float_position_schema_v2";
    public static final String K_POSITION_SIDE = "float_position_side_v2";
    public static final String K_POSITION_Y_BP = "float_position_y_bp_v2";

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
    }

    private int legacyInt(String key, int def) {
        Object raw = p.getAll().get(key);
        return raw instanceof Number n ? n.intValue() : def;
    }

    private static int toBasisPoints(int y, int availableHeight) {
        return FloatingPositionMath.basisPointsFromY(y, availableHeight, 3333);
    }

    public float alpha() { return clamp(p.getInt(K_ALPHA, 62), 10, 100) / 100f; }
    public int sizeDp() { return clamp(p.getInt(K_SIZE, 48), 24, 96); }
    public int showPercentage() { return clamp(p.getInt(K_SHOW_PERCENT, 72), 10, 100); }
    public int hiddenPercent() { return 100 - showPercentage(); }
    public int longPressMs() { return clamp(p.getInt(K_LONG_PRESS, 300), 100, 1500); }
    public int doubleTapMs() { return clamp(p.getInt(K_DOUBLE_TAP, 200), 120, 800); }
    public int tapMaxMs() { return clamp(p.getInt(K_TAP_MAX_MS, 150), 80, 400); }
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
    public boolean keepNavigationBarInScreenshot() { return p.getBoolean(K_KEEP_NAVIGATION_BAR, false); }
    public boolean accessibilityScreenshot() { return p.getBoolean(K_ACCESSIBILITY_SCREENSHOT, true); }
    public int circleEngine() { return clamp(p.getInt(K_CIRCLE_ENGINE, 0), 0, 1); }
    public boolean circleBorderEnabled() { return p.getBoolean(K_CIRCLE_BORDER_ENABLED, true); }
    public int circleBorderColor() { return p.getInt(K_CIRCLE_BORDER_COLOR, DEFAULT_CIRCLE_BORDER_COLOR); }
    public int circleBorderWidthDp() { return clamp(p.getInt(K_CIRCLE_BORDER_WIDTH_DP, 3), 1, 8); }
    public boolean circleHybridOcr() { return circleCorrectionEngine() != 0; }
    public int circleFullOcrEngine() {
        Object raw = p.getAll().get(K_CIRCLE_FULL_OCR_ENGINE);
        if (raw instanceof Number n) return clamp(n.intValue(), 0, 3);
        if (raw instanceof String v) { try { return clamp(Integer.parseInt(v.trim()), 0, 3); } catch (Throwable ignored) {} }
        return 0;
    }
    public int circleCorrectionEngine() {
        Object raw = p.getAll().get(K_CIRCLE_CORRECTION_ENGINE);
        if (raw instanceof Number n) return clamp(n.intValue(), 0, 3);
        if (raw instanceof String v) { try { return clamp(Integer.parseInt(v.trim()), 0, 3); } catch (Throwable ignored) {} }
        return p.getBoolean(K_CIRCLE_HYBRID_OCR, true) ? 2 : 0;
    }
    public boolean rootScreenshot() { return p.getBoolean(K_ROOT_SCREENSHOT, false); }
    public boolean longPressDragEnabled() { return p.getBoolean(K_LONG_PRESS_DRAG, true); }
    public boolean quickMoveEnabled() { return p.getBoolean(K_QUICK_MOVE, true); }
    public boolean diagnosticLogging() { return p.getBoolean(K_DIAGNOSTIC, false); }
    public boolean imeAvoid() { return p.getBoolean(K_IME_AVOID, true); }
    public boolean defaultHideByApp() { return p.getBoolean(K_GLOBAL_DEFAULT_HIDE, false); }
    public boolean hideWithoutNotify() { return p.getBoolean(K_HIDE_ICON_NO_NOTIFY, false); }
    public boolean edgeSwipeRecallEnabled() { return p.getBoolean(K_HIDE_MAIN_SWIPE, true); }

    public boolean enhancedMode() { return p.getBoolean(K_ENHANCED_MODE, false); }
    public boolean rootEnabled() { return p.getBoolean(K_ROOT_ENABLED, false); }
    public boolean lsposedEnabled() { return p.getBoolean(K_LSPOSED_ENABLED, false); }
    public boolean lsposedSecureScreenshot() { return p.getBoolean(K_LSPOSED_SECURE_SCREENSHOT, false); }
    public boolean privilegeFallback() { return p.getBoolean(K_PRIVILEGE_FALLBACK, true); }
    public boolean canUseRoot() { return enhancedMode() && rootEnabled(); }
    public boolean canUseLsposed() { return PrivilegeManager.canUseLsposed(this); }
    public boolean effectiveRootScreenshot() { return canUseRoot() && rootScreenshot(); }
    public boolean effectiveLsposedSecureScreenshot() {
        return PrivilegeManager.canUseLsposedSecureScreenshot(this);
    }
    public long rootLastCheckMs() { return p.getLong(K_ROOT_LAST_CHECK, 0L); }
    public boolean rootLastGranted() { return p.getBoolean(K_ROOT_LAST_GRANTED, false); }
    public String rootLastDetail() { return p.getString(K_ROOT_LAST_DETAIL, ""); }

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
    public int ocrEngineMode() {
        Object raw = p.getAll().get(K_OCR_ENGINE);
        if (raw instanceof Number n) return clamp(n.intValue(), 0, 3);
        if (raw instanceof String v) { try { return clamp(Integer.parseInt(v.trim()), 0, 3); } catch (Throwable ignored) {} }
        return 0;
    }
    public String customIconUri() { return p.getString(K_CUSTOM_ICON, ""); }
    public String slidePics() { return p.getString(K_SLIDE_PICS, ""); }
    public int slideIntervalMs() { return clamp(p.getInt(K_SLIDE_INTERVAL, 3000), 500, 60000); }
    public String action(String key, String def) { return p.getString(key, def); }
    public String hiddenPackagesRaw() { return p.getString(K_HIDE_PACKAGES, ""); }
    public int circleCancelXBp() { return p.getInt(K_CIRCLE_CANCEL_X_BP, -1); }
    public int circleCancelYBp() { return p.getInt(K_CIRCLE_CANCEL_Y_BP, -1); }

    public void armGoogleCtsSession(String token, long untilElapsed) {
        p.edit()
                .putString(K_GOOGLE_CTS_ACTIVE_SESSION, token == null ? "" : token)
                .putLong(K_GOOGLE_CTS_ACTIVE_UNTIL, Math.max(0L, untilElapsed))
                .commit();
    }

    public void clearGoogleCtsSession() {
        p.edit()
                .remove(K_GOOGLE_CTS_ACTIVE_SESSION)
                .remove(K_GOOGLE_CTS_ACTIVE_UNTIL)
                .apply();
    }

    public void setBoolean(String key, boolean value) {
        if (key != null && !key.isBlank()) p.edit().putBoolean(key, value).apply();
    }

    public void setInt(String key, int value) {
        if (key != null && !key.isBlank()) p.edit().putInt(key, value).apply();
    }

    public void setString(String key, String value) {
        if (key != null && !key.isBlank()) p.edit().putString(key, value == null ? "" : value).apply();
    }

    /** Observe settings changes without exposing the backing SharedPreferences object. */
    public void registerChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        if (listener != null) p.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        if (listener != null) p.unregisterOnSharedPreferenceChangeListener(listener);
    }

    /** Migration-only helper: replace every stored action id without exposing SharedPreferences. */
    void replaceActionValue(String oldValue, String replacement) {
        if (oldValue == null || oldValue.isBlank()) return;
        String next = replacement == null ? ActionId.NONE : replacement;
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            if (!(entry.getValue() instanceof String value) || !oldValue.equals(value)) continue;
            if (editor == null) editor = p.edit();
            editor.putString(entry.getKey(), next);
        }
        if (editor != null) editor.apply();
    }

    public void setLineColors(String value) { setString(K_LINE_COLORS, value); }
    public void setHiddenPackagesRaw(String value) { setString(K_HIDE_PACKAGES, value); }

    /** Root authorization result is one logical state and is committed atomically. */
    public void saveRootCheck(boolean granted, long checkedAt, String detail) {
        p.edit()
                .putBoolean(K_ROOT_LAST_GRANTED, granted)
                .putLong(K_ROOT_LAST_CHECK, Math.max(0L, checkedAt))
                .putString(K_ROOT_LAST_DETAIL, detail == null ? "" : detail)
                .apply();
    }

    /** Circle close-button position is one logical x/y state and is committed atomically. */
    public void saveCircleCancelPosition(int xBp, int yBp) {
        p.edit()
                .putInt(K_CIRCLE_CANCEL_X_BP, clamp(xBp, 0, 10000))
                .putInt(K_CIRCLE_CANCEL_Y_BP, clamp(yBp, 0, 10000))
                .apply();
    }

    /** Compound icon selection stays atomic so URI and style cannot drift apart. */
    public void selectCustomIcon(String uri) {
        p.edit().putString(K_CUSTOM_ICON, uri == null ? "" : uri).putInt(K_STYLE, 3).apply();
    }

    /** Compound slideshow selection stays atomic so URI list and style cannot drift apart. */
    public void selectSlideIcons(String joinedUris) {
        p.edit().putString(K_SLIDE_PICS, joinedUris == null ? "" : joinedUris).putInt(K_STYLE, 4).apply();
    }

    public Set<String> hiddenPackages() {
        String raw = hiddenPackagesRaw();
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

    /** Legacy absolute-position readers kept only for rollback/source compatibility. */
    public boolean hasSavedX() { return p.contains(posXKey()) || p.contains(K_POS_X); }
    public int savedX(int def) { return p.getInt(posXKey(), p.getInt(K_POS_X, def)); }
    public int savedY(int def) { return p.getInt(posYKey(), p.getInt(K_POS_Y, def)); }

    /** Canonical V2 side. Orientation/fullscreen changes must never rewrite this value. */
    public int savedSide(int def) {
        return clamp(legacyInt(K_POSITION_SIDE, def), 0, 1);
    }

    public void saveSide(boolean left) { setInt(K_POSITION_SIDE, left ? 0 : 1); }

    /** Resolve the normalized V2 vertical position against the current display. */
    public int savedPositionY(int availableHeight, int defaultY) {
        if (availableHeight <= 0) return Math.max(0, defaultY);
        int defBp = toBasisPoints(defaultY, availableHeight);
        int yBp = clamp(legacyInt(K_POSITION_Y_BP, defBp), 0, 10000);
        return FloatingPositionMath.yFromBasisPoints(yBp, availableHeight);
    }

    public int savedPositionYBasisPoints() {
        return clamp(legacyInt(K_POSITION_Y_BP, 3333), 0, 10000);
    }

    /**
     * User drag is the only writer for the canonical floating position.
     * X is deliberately ignored after deciding the side in the current geometry.
     */
    public boolean savePosition(boolean left, int x, int y) {
        android.graphics.Rect bounds = ScreenGeometry.displayBounds(context);
        int iconPx = Math.round(sizeDp() * context.getResources().getDisplayMetrics().density);
        int availableHeight = Math.max(0, bounds.height() - iconPx);
        int currentBp = savedPositionYBasisPoints();
        int yBp = availableHeight > 0 ? toBasisPoints(y, availableHeight) : currentBp;
        return p.edit()
                .putInt(K_POSITION_SCHEMA, POSITION_SCHEMA_VERSION)
                .putInt(K_POSITION_SIDE, left ? 0 : 1)
                .putInt(K_POSITION_Y_BP, yBp)
                .commit();
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
