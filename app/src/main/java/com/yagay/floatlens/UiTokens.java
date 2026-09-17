package com.yagay.floatlens;

import android.content.Context;

/** Shared visual tokens for settings, result panels and floating action menus. */
final class UiTokens {
    static boolean dark(Context c) { return ThemeSettings.isDark(c); }

    static int background(Context c) { return dark(c) ? 0xFF0E1013 : 0xFFF4F5F7; }
    static int surface(Context c) { return dark(c) ? 0xFF181B20 : 0xFFFFFFFF; }
    static int surfaceAlt(Context c) { return dark(c) ? 0xFF23272E : 0xFFF0F2F5; }
    static int textPrimary(Context c) { return dark(c) ? 0xFFF4F5F7 : 0xFF17191D; }
    static int textSecondary(Context c) { return dark(c) ? 0xFFAEB4BE : 0xFF69707B; }
    static int outline(Context c) { return dark(c) ? 0xFF292E35 : 0xFFE6E8EC; }
    static int success(Context c) { return dark(c) ? 0xFF7ED7A2 : 0xFF197A45; }
    static int warning(Context c) { return dark(c) ? 0xFFFFC266 : 0xFFA05A00; }
    static int successSurface(Context c) { return dark(c) ? 0xFF173527 : 0xFFE9F6EE; }
    static int warningSurface(Context c) { return dark(c) ? 0xFF3A2A13 : 0xFFFFF1DF; }
    static int accent(Context c) { return dark(c) ? 0xFF9CC2FF : 0xFF285FBE; }
    static int ripple(Context c) { return dark(c) ? 0x22FFFFFF : 0x12000000; }

    static int menuSurface(Context c) { return dark(c) ? 0xFF2B2B2B : 0xFFF8F8F8; }
    static int resultSurface(Context c) { return dark(c) ? 0xF0202124 : 0xF8FFFFFF; }

    static int dp(Context c, float value) { return ScreenGeometry.dp(c, value); }

    private UiTokens() {}
}
