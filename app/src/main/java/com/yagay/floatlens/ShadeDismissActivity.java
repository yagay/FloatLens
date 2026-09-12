package com.yagay.floatlens;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Modern non-root equivalent of FV ShadowActivity request=21.
 *
 * FV starts ShadowActivity and finishes it 300 ms later. Android 12+ also documents that starting an
 * Activity while the app has a window above the notification drawer can cause the drawer to close.
 * This Activity is intentionally empty/translucent and exists only as a compatibility fallback after
 * GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE failed and the shade is still confirmed expanded.
 */
public final class ShadeDismissActivity extends Activity {
    private static final String EXTRA_REASON = "shade_reason";
    private static final long FL_FINISH_DELAY_MS = 300L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable finishRunnable = () -> {
        if (!isFinishing() && !isDestroyed()) finish();
        overridePendingTransition(0, 0);
    };

    static boolean launch(Context context, String reason) {
        if (context == null) return false;
        try {
            Intent intent = new Intent(context, ShadeDismissActivity.class)
                    .putExtra(EXTRA_REASON, reason == null ? "unknown" : reason)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(intent);
            DiagnosticLog.i(context.getApplicationContext(), "FL_SHADE",
                    "shadow activity launch reason=" + reason + " finishDelayMs=" + FL_FINISH_DELAY_MS);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(context.getApplicationContext(), "FL_SHADE",
                    "shadow activity launch failed reason=" + reason + " error=" + t);
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0f);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        View empty = new View(this);
        empty.setBackgroundColor(Color.TRANSPARENT);
        setContentView(empty);
        overridePendingTransition(0, 0);

        String reason = getIntent() == null ? "unknown"
                : getIntent().getStringExtra(EXTRA_REASON);
        DiagnosticLog.i(this, "FL_SHADE", "shadow activity created reason=" + reason);
        main.postDelayed(finishRunnable, FL_FINISH_DELAY_MS);
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(finishRunnable);
        DiagnosticLog.i(this, "FL_SHADE", "shadow activity destroyed");
        super.onDestroy();
    }
}
