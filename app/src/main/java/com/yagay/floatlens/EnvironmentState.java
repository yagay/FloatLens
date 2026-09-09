package com.yagay.floatlens;

/** Immutable environment snapshot without Java record desugaring. */
public final class EnvironmentState {
    private final String topPackage;
    private final boolean imeVisible;
    private final int imeTopPx;
    private final boolean statusBarVisible;
    private final boolean notificationExpanded;
    private final boolean fullscreen;

    public EnvironmentState(String topPackage, boolean imeVisible, int imeTopPx,
                            boolean statusBarVisible, boolean notificationExpanded,
                            boolean fullscreen) {
        this.topPackage = topPackage == null ? "" : topPackage;
        this.imeVisible = imeVisible;
        this.imeTopPx = imeTopPx;
        this.statusBarVisible = statusBarVisible;
        this.notificationExpanded = notificationExpanded;
        this.fullscreen = fullscreen;
    }

    public String topPackage() { return topPackage; }
    public boolean imeVisible() { return imeVisible; }
    public int imeTopPx() { return imeTopPx; }
    public boolean statusBarVisible() { return statusBarVisible; }
    public boolean notificationExpanded() { return notificationExpanded; }
    public boolean fullscreen() { return fullscreen; }
}
