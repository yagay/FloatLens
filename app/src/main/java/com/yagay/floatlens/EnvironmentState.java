package com.yagay.floatlens;

public record EnvironmentState(String topPackage, boolean imeVisible, int imeTopPx, boolean statusBarVisible, boolean notificationExpanded, boolean fullscreen) {}
