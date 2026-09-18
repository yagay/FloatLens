package com.yagay.floatlens;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

/** Receives the Android Contextual Search frozen frame from the system_server LSPosed hook. */
public final class GoogleCtsBridgeActivity extends Activity {
    static final String ACTION = "com.yagay.floatlens.action.GOOGLE_CTS_CAPTURE";
    static final String EXTRA_SCREENSHOT = "com.yagay.floatlens.extra.GOOGLE_CTS_SCREENSHOT";
    static final String EXTRA_ENTRYPOINT = "com.yagay.floatlens.extra.GOOGLE_CTS_ENTRYPOINT";
    static final String EXTRA_SECURE_FOUND = "com.yagay.floatlens.extra.GOOGLE_CTS_SECURE_FOUND";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @SuppressWarnings("deprecation")
    private void handle(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            DiagnosticLog.i(this, "GOOGLE_CTS_BRIDGE", "rejected invalid action");
            finishQuietly();
            return;
        }

        Object raw;
        try {
            raw = intent.getParcelableExtra(EXTRA_SCREENSHOT);
        } catch (Throwable t) {
            DiagnosticLog.i(this, "GOOGLE_CTS_BRIDGE",
                    "screenshot unparcel failed=" + ScreenCaptureBackend.safeMessage(t));
            finishQuietly();
            return;
        }
        if (!(raw instanceof Bitmap screenshot)
                || screenshot.isRecycled()
                || screenshot.getWidth() <= 0
                || screenshot.getHeight() <= 0) {
            DiagnosticLog.i(this, "GOOGLE_CTS_BRIDGE", "missing or invalid screenshot");
            finishQuietly();
            return;
        }

        int entrypoint = intent.getIntExtra(EXTRA_ENTRYPOINT, -1);
        boolean secureFound = intent.getBooleanExtra(EXTRA_SECURE_FOUND, false);
        Bitmap owned = null;
        try {
            owned = screenshot.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable ignored) {
        }
        if (owned == null) owned = screenshot;

        DiagnosticLog.i(this, "GOOGLE_CTS_BRIDGE",
                "received entrypoint=" + entrypoint
                        + " secureFound=" + secureFound
                        + " source=" + screenshot.getWidth() + "x" + screenshot.getHeight()
                        + " ownedCopy=" + (owned != screenshot));

        boolean shown = FLCircleController.showCaptured(
                getApplicationContext(), owned, "google_cts_system");
        if (!shown && owned != screenshot && !owned.isRecycled()) {
            try {
                owned.recycle();
            } catch (Throwable ignored) {
            }
        }
        DiagnosticLog.i(this, "GOOGLE_CTS_BRIDGE", "workspace shown=" + shown);
        finishQuietly();
    }

    private void finishQuietly() {
        try {
            finish();
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
    }
}
