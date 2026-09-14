package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FloatPreferenceImpactTest {
    @Test
    public void appearanceKeysRequireWindowRefresh() {
        assertEquals(FloatPreferenceImpact.Impact.APPEARANCE_LAYOUT,
                FloatPreferenceImpact.classify(FloatSettings.K_SIZE));
        assertEquals(FloatPreferenceImpact.Impact.APPEARANCE_LAYOUT,
                FloatPreferenceImpact.classify(FloatSettings.K_STYLE));
        assertEquals(FloatPreferenceImpact.Impact.APPEARANCE_LAYOUT,
                FloatPreferenceImpact.classify(FloatSettings.K_BOTH_SIDE));
    }

    @Test
    public void visibilityKeysAvoidWindowRelayout() {
        assertEquals(FloatPreferenceImpact.Impact.VISIBILITY,
                FloatPreferenceImpact.classify(FloatSettings.K_HIDE_PACKAGES));
        assertEquals(FloatPreferenceImpact.Impact.VISIBILITY,
                FloatPreferenceImpact.classify(FloatSettings.K_HIDE_FULLSCREEN));
        assertEquals(FloatPreferenceImpact.Impact.VISIBILITY,
                FloatPreferenceImpact.classify(FloatSettings.K_IME_AVOID));
    }

    @Test
    public void gestureThresholdsOnlyRefreshIconSettings() {
        assertEquals(FloatPreferenceImpact.Impact.ICON_SETTINGS,
                FloatPreferenceImpact.classify(FloatSettings.K_LONG_PRESS));
        assertEquals(FloatPreferenceImpact.Impact.ICON_SETTINGS,
                FloatPreferenceImpact.classify(FloatSettings.K_GESTURE_START_DISTANCE));
    }

    @Test
    public void ocrAndPrivilegeChangesAreServiceOnly() {
        assertEquals(FloatPreferenceImpact.Impact.SERVICE_SETTINGS,
                FloatPreferenceImpact.classify(FloatSettings.K_OCR_ENGINE));
        assertEquals(FloatPreferenceImpact.Impact.SERVICE_SETTINGS,
                FloatPreferenceImpact.classify(FloatSettings.K_ROOT_ENABLED));
    }

    @Test
    public void persistedPositionsDoNotTriggerRefreshLoop() {
        assertEquals(FloatPreferenceImpact.Impact.IGNORE,
                FloatPreferenceImpact.classify(FloatSettings.K_POS_X_PORTRAIT));
        assertEquals(FloatPreferenceImpact.Impact.IGNORE,
                FloatPreferenceImpact.classify(FloatSettings.K_POS_Y_LANDSCAPE));
    }
}
