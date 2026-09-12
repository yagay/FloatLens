package com.yagay.floatlens;

/** Pure state owner for every reason the floating icon may be hidden. */
final class FloatVisibilityController {
    private boolean manualHidden;
    private boolean screenshotHidden;
    private boolean appHidden;
    private boolean lockHidden;
    private boolean fullscreenHidden;
    private boolean rawFullscreen;
    private boolean imeVisible;
    private boolean notificationExpanded;
    private boolean statusBarVisible = true;
    private String topPackage = "";
    private int imeTopPx;

    void setManualHidden(boolean hidden) { manualHidden = hidden; }
    void setScreenshotHidden(boolean hidden) { screenshotHidden = hidden; }
    void setLockHidden(boolean hidden) { lockHidden = hidden; }

    void applyEnvironment(FloatSettings settings, EnvironmentState state) {
        if (state == null) return;
        topPackage = state.topPackage();
        imeVisible = state.imeVisible();
        imeTopPx = state.imeTopPx();
        notificationExpanded = state.notificationExpanded();
        statusBarVisible = state.statusBarVisible();
        rawFullscreen = state.fullscreen();
        applySettings(settings);
    }

    void applySettings(FloatSettings settings) {
        if (settings == null) return;
        appHidden = settings.shouldHideForPackage(topPackage);
        fullscreenHidden = settings.hideWhenFullscreen() && rawFullscreen;
    }

    boolean visible() {
        return !(manualHidden || screenshotHidden || appHidden || lockHidden || fullscreenHidden);
    }

    boolean wakeEdgesNeeded(FloatSettings settings) {
        return manualHidden && settings != null
                && settings.prefs().getBoolean(FloatSettings.K_HIDE_MAIN_SWIPE, true)
                && !screenshotHidden && !lockHidden;
    }

    boolean manualHidden() { return manualHidden; }
    boolean screenshotHidden() { return screenshotHidden; }
    boolean appHidden() { return appHidden; }
    boolean lockHidden() { return lockHidden; }
    boolean fullscreenHidden() { return fullscreenHidden; }
    boolean imeVisible() { return imeVisible; }
    int imeTopPx() { return imeTopPx; }
    boolean notificationExpanded() { return notificationExpanded; }
    boolean statusBarVisible() { return statusBarVisible; }
    String topPackage() { return topPackage; }

    String diagnostic() {
        return "visible=" + visible()
                + " manual=" + manualHidden
                + " shot=" + screenshotHidden
                + " app=" + appHidden
                + " lock=" + lockHidden
                + " full=" + fullscreenHidden
                + " ime=" + imeVisible
                + " notif=" + notificationExpanded
                + " status=" + statusBarVisible;
    }
}
