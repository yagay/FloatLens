package com.yagay.floatlens;

import android.os.Handler;
import android.os.Looper;

/** Keeps BrowserAiBridge progressing even when the browser does not emit a useful accessibility event. */
final class BrowserAiBridgeTicker {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean running;

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (!BrowserAiBridge.isRunning()) {
                running = false;
                return;
            }
            LensAccessibilityService service = LensAccessibilityService.get();
            if (service != null) BrowserAiBridge.onAccessibilityEvent(service, null);
            MAIN.postDelayed(this, 360L);
        }
    };

    static void start() {
        if (running) return;
        running = true;
        MAIN.removeCallbacks(TICK);
        MAIN.post(TICK);
    }

    static void stop() {
        running = false;
        MAIN.removeCallbacks(TICK);
    }

    private BrowserAiBridgeTicker() {}
}
