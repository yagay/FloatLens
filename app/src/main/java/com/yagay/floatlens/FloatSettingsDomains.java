package com.yagay.floatlens;

/**
 * Focused read-only views over FloatSettings.
 *
 * <p>FloatSettings remains the compatibility/persistence boundary, while runtime components depend
 * only on the settings domain they actually use.</p>
 */
final class FloatSettingsDomains {
    static Icon icon(FloatSettings settings) { return new Icon(settings); }
    static Gesture gesture(FloatSettings settings) { return new Gesture(settings); }
    static Capture capture(FloatSettings settings) { return new Capture(settings); }
    static Circle circle(FloatSettings settings) { return new Circle(settings); }
    static Visibility visibility(FloatSettings settings) { return new Visibility(settings); }
    static Privilege privilege(FloatSettings settings) { return new Privilege(settings); }

    static final class Icon {
        private final FloatSettings s;
        Icon(FloatSettings s) { this.s = s; }
        int sizeDp() { return s.sizeDp(); }
        float alpha() { return s.alpha(); }
        int showPercentage() { return s.showPercentage(); }
        int hiddenPercent() { return s.hiddenPercent(); }
        int style() { return s.style(); }
        boolean bothSide() { return s.bothSide(); }
        boolean snap() { return s.snap(); }
        int savedSide(int def) { return s.savedSide(def); }
        int savedY(int availableHeight, int defaultY) {
            return s.savedPositionY(availableHeight, defaultY);
        }
        int yBasisPoints() { return s.savedPositionYBasisPoints(); }
        boolean savePosition(boolean left, int x, int y) { return s.savePosition(left, x, y); }
    }

    static final class Gesture {
        private final FloatSettings s;
        Gesture(FloatSettings s) { this.s = s; }
        int longPressMs() { return s.longPressMs(); }
        int doubleTapMs() { return s.doubleTapMs(); }
        int tapMaxMs() { return s.tapMaxMs(); }
        int downShortDistance() { return s.downShortDistance(); }
        int sideShortDistance() { return s.sideShortDistance(); }
        int gestureStartDistance() { return s.gestureStartDistance(); }
        float verticalBias() { return s.verticalBias(); }
        boolean vibrate() { return s.vibrate(); }
        boolean track() { return s.track(); }
        boolean longPressDragEnabled() { return s.longPressDragEnabled(); }
        boolean quickMoveEnabled() { return s.quickMoveEnabled(); }
    }

    static final class Capture {
        private final FloatSettings s;
        Capture(FloatSettings s) { this.s = s; }
        boolean keepIcon() { return s.keepInScreenshot(); }
        boolean keepStatusBar() { return s.keepStatusBarInScreenshot(); }
        boolean keepNavigationBar() { return s.keepNavigationBarInScreenshot(); }
        boolean accessibilityPreferred() { return s.accessibilityScreenshot(); }
        boolean rootFeatureEnabled() { return s.rootScreenshot(); }
        boolean rootAllowed() { return s.effectiveRootScreenshot(); }
        boolean secureLsposedFeatureEnabled() { return s.lsposedSecureScreenshot(); }
        boolean fallbackNormal() { return s.privilegeFallback(); }
    }

    static final class Circle {
        private final FloatSettings s;
        Circle(FloatSettings s) { this.s = s; }
        int engine() { return s.circleEngine(); }
        boolean borderEnabled() { return s.circleBorderEnabled(); }
        int borderColor() { return s.circleBorderColor(); }
        int borderWidthDp() { return s.circleBorderWidthDp(); }
        int fullOcrEngine() { return s.circleFullOcrEngine(); }
        int correctionEngine() { return s.circleCorrectionEngine(); }
        boolean hybridOcr() { return s.circleHybridOcr(); }
    }

    static final class Visibility {
        private final FloatSettings s;
        Visibility(FloatSettings s) { this.s = s; }
        boolean hideForPackage(String pkg) { return s.shouldHideForPackage(pkg); }
        boolean hideWhenFullscreen() { return s.hideWhenFullscreen(); }
        boolean edgeSwipeRecallEnabled() { return s.edgeSwipeRecallEnabled(); }
        boolean showOnLock() { return s.showOnLock(); }
        boolean imeAvoid() { return s.imeAvoid(); }
    }

    static final class Privilege {
        private final FloatSettings s;
        Privilege(FloatSettings s) { this.s = s; }
        boolean enhanced() { return s.enhancedMode(); }
        boolean rootEnabled() { return s.rootEnabled(); }
        boolean lsposedEnabled() { return s.lsposedEnabled(); }
        boolean secureScreenshotEnabled() { return s.lsposedSecureScreenshot(); }
        boolean fallbackNormal() { return s.privilegeFallback(); }
    }

    private FloatSettingsDomains() {}
}
