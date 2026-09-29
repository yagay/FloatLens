package com.yagay.YFloat;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Opens the exact YFloat accessibility service entry when the system Settings app supports it. */
final class AccessibilitySettingsNavigator {
    private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    private AccessibilitySettingsNavigator() {}

    static ComponentName expectedComponent(Context context) {
        return new ComponentName(context.getPackageName(), LensAccessibilityService.class.getName());
    }

    static boolean open(Context context) {
        if (context == null) return false;
        ComponentName expected = expectedComponent(context);

        Intent details = new Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, expected);
        if (!(context instanceof Activity)) details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(details);
            DiagnosticLog.critical(context, "A11Y_SETTINGS",
                    "opened details for " + expected.flattenToString());
            return true;
        } catch (Throwable detailsFailure) {
            DiagnosticLog.critical(context, "A11Y_SETTINGS",
                    "details unavailable for " + expected.flattenToString()
                            + "; fallback=" + detailsFailure.getClass().getSimpleName());
        }

        Intent generic = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (!(context instanceof Activity)) generic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(generic);
            return true;
        } catch (Throwable genericFailure) {
            DiagnosticLog.critical(context, "A11Y_SETTINGS",
                    "generic accessibility settings unavailable: "
                            + genericFailure.getClass().getSimpleName());
            return false;
        }
    }
}
