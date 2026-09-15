package com.yagay.floatlens;

/** Local app preference gate for optional LSPosed View-content enrichment. */
public final class LsposedViewContentPolicy {
    public static final String K_ENABLED = "lsposed_view_content_enhancement_v1";

    public static boolean selected(FloatSettings settings) {
        return settings != null && settings.prefs().getBoolean(K_ENABLED, false);
    }

    public static boolean effective(FloatSettings settings) {
        return settings != null
                && settings.enhancedMode()
                && settings.lsposedEnabled()
                && selected(settings)
                && PrivilegeManager.lsposedProviderAvailable();
    }

    private LsposedViewContentPolicy() {}
}
