package com.yagay.floatlens;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Persistent app appearance preference with system/light/dark modes. */
final class ThemeSettings {
    static final int MODE_SYSTEM = 0;
    static final int MODE_LIGHT = 1;
    static final int MODE_DARK = 2;

    private static final String PREFS = "floatlens_ui";
    private static final String KEY_THEME_MODE = "theme_mode";

    static int mode(Context c) {
        if (c == null) return MODE_SYSTEM;
        return clamp(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_THEME_MODE, MODE_SYSTEM));
    }

    static boolean setMode(Context c, int mode) {
        if (c == null) return false;
        int next = clamp(mode);
        int old = mode(c);
        if (old == next) return false;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME_MODE, next).apply();
        applyMode(next);
        return true;
    }

    static void applySavedMode(Context c) {
        applyMode(mode(c));
    }

    static boolean isDark(Context c) {
        int mode = mode(c);
        if (mode == MODE_DARK) return true;
        if (mode == MODE_LIGHT) return false;
        if (c == null) return false;
        int night = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    static void applySystemBars(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try {
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                    activity.getWindow(), activity.getWindow().getDecorView());
            boolean light = !isDark(activity);
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) { }
    }

    private static void applyMode(int mode) {
        int appCompatMode = switch (clamp(mode)) {
            case MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO;
            case MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES;
            default -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        };
        AppCompatDelegate.setDefaultNightMode(appCompatMode);
    }

    private static int clamp(int mode) {
        if (mode < MODE_SYSTEM || mode > MODE_DARK) return MODE_SYSTEM;
        return mode;
    }

    private ThemeSettings() { }
}
