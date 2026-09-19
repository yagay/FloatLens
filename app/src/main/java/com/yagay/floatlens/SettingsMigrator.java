package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

/** Runs preference schema migrations once from Application startup; settings reads stay side-effect free. */
final class SettingsMigrator {
    static void run(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);

        if (!p.contains(FloatSettings.K_SHOW_PERCENT) && p.contains("float_edge_hide_percent")) {
            int hidden = clamp(p.getInt("float_edge_hide_percent", 28), 0, 90);
            p.edit().putInt(FloatSettings.K_SHOW_PERCENT, 100 - hidden).apply();
        }
        if (!p.contains(FloatSettings.K_KEEP_IN_SCREENSHOT)
                && p.contains("setting_screenshot_keep_float_icon")) {
            p.edit().putBoolean(FloatSettings.K_KEEP_IN_SCREENSHOT,
                    p.getBoolean("setting_screenshot_keep_float_icon", false)).apply();
        }

        Object oldFullscreen = p.getAll().get(FloatSettings.K_HIDE_FULLSCREEN);
        if (oldFullscreen instanceof Boolean value) {
            p.edit().putInt(FloatSettings.K_HIDE_FULLSCREEN, value ? 2 : 0).apply();
        }

        if (!p.getBoolean(FloatSettings.K_MIGRATE_LONG_PRESS_CONFIG_V1, false)) {
            SharedPreferences.Editor editor = p.edit();
            if (ActionId.OCR.equals(p.getString(FloatSettings.K_ACTION_LONG, null))) {
                editor.remove(FloatSettings.K_ACTION_LONG);
            }
            editor.putBoolean(FloatSettings.K_MIGRATE_LONG_PRESS_CONFIG_V1, true).apply();
        }

        migratePositionV2(app, p);
    }

    private static void migratePositionV2(Context app, SharedPreferences p) {
        int schema = legacyInt(p, FloatSettings.K_POSITION_SCHEMA, 0);
        if (schema >= FloatSettings.POSITION_SCHEMA_VERSION
                && p.contains(FloatSettings.K_POSITION_SIDE)
                && p.contains(FloatSettings.K_POSITION_Y_BP)) {
            return;
        }

        int side = validSide(p, FloatSettings.K_GRAVITY);
        if (side < 0) side = validSide(p, FloatSettings.K_GRAVITY_LAND);
        if (side < 0) side = 1;

        Rect bounds = ScreenGeometry.displayBounds(app);
        int sizeDp = clamp(p.getInt(FloatSettings.K_SIZE, 48), 24, 96);
        int iconPx = Math.round(sizeDp * app.getResources().getDisplayMetrics().density);
        int portraitAvailable = Math.max(0, Math.max(bounds.width(), bounds.height()) - iconPx);
        int landscapeAvailable = Math.max(0, Math.min(bounds.width(), bounds.height()) - iconPx);

        int yBp = 3333;
        if (p.contains(FloatSettings.K_POS_Y_PORTRAIT) && portraitAvailable > 0) {
            yBp = toBasisPoints(legacyInt(p, FloatSettings.K_POS_Y_PORTRAIT,
                    portraitAvailable / 3), portraitAvailable);
        } else if (p.contains(FloatSettings.K_POS_Y) && portraitAvailable > 0) {
            yBp = toBasisPoints(legacyInt(p, FloatSettings.K_POS_Y,
                    portraitAvailable / 3), portraitAvailable);
        } else if (p.contains(FloatSettings.K_POS_Y_LANDSCAPE) && landscapeAvailable > 0) {
            yBp = toBasisPoints(legacyInt(p, FloatSettings.K_POS_Y_LANDSCAPE,
                    landscapeAvailable / 3), landscapeAvailable);
        }

        p.edit()
                .putInt(FloatSettings.K_POSITION_SCHEMA, FloatSettings.POSITION_SCHEMA_VERSION)
                .putInt(FloatSettings.K_POSITION_SIDE, side)
                .putInt(FloatSettings.K_POSITION_Y_BP, yBp)
                .commit();
        DiagnosticLog.i(app, "SETTINGS_MIGRATION",
                "position schema=" + FloatSettings.POSITION_SCHEMA_VERSION
                        + " side=" + (side == 0 ? "L" : "R") + " yBp=" + yBp);
    }

    private static int validSide(SharedPreferences p, String key) {
        if (!p.contains(key)) return -1;
        int side = legacyInt(p, key, -1);
        return side == 0 || side == 1 ? side : -1;
    }

    private static int legacyInt(SharedPreferences p, String key, int def) {
        Object raw = p.getAll().get(key);
        return raw instanceof Number number ? number.intValue() : def;
    }

    private static int toBasisPoints(int y, int availableHeight) {
        if (availableHeight <= 0) return 3333;
        return clamp(Math.round(Math.max(0, y) * 10000f / availableHeight), 0, 10000);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private SettingsMigrator() {}
}
