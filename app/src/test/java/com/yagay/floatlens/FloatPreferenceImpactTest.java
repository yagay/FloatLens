package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

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
    public void circleBorderChangesRefreshOnlyCircleOverlay() {
        assertEquals(FloatPreferenceImpact.Impact.CIRCLE_OVERLAY,
                FloatPreferenceImpact.classify(FloatSettings.K_CIRCLE_BORDER_ENABLED));
        assertEquals(FloatPreferenceImpact.Impact.CIRCLE_OVERLAY,
                FloatPreferenceImpact.classify(FloatSettings.K_CIRCLE_BORDER_COLOR));
        assertEquals(FloatPreferenceImpact.Impact.CIRCLE_OVERLAY,
                FloatPreferenceImpact.classify(FloatSettings.K_CIRCLE_BORDER_WIDTH_DP));
    }

    @Test
    public void everyPublicSettingKeyHasExplicitImpact() throws Exception {
        for (Field field : FloatSettings.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers) || field.getType() != String.class
                    || !field.getName().startsWith("K_")) {
                continue;
            }
            String key = (String) field.get(null);
            assertTrue("Missing impact classification for " + field.getName() + "=" + key,
                    FloatPreferenceImpact.isExplicitlyClassified(key));
        }
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
                FloatPreferenceImpact.classify(FloatSettings.K_POSITION_SIDE));
        assertEquals(FloatPreferenceImpact.Impact.IGNORE,
                FloatPreferenceImpact.classify(FloatSettings.K_POSITION_Y_BP));
        assertEquals(FloatPreferenceImpact.Impact.IGNORE,
                FloatPreferenceImpact.classify(FloatSettings.K_POS_X_PORTRAIT));
        assertEquals(FloatPreferenceImpact.Impact.IGNORE,
                FloatPreferenceImpact.classify(FloatSettings.K_POS_Y_LANDSCAPE));
    }
}
