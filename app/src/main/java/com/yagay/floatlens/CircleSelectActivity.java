package com.yagay.floatlens;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Activity-backed host for Circle Select.
 *
 * Region screenshot already proved reliable on OxygenOS because a real foreground Activity exists
 * before the FV-style shade-dismiss fallback runs. Circle Select uses the exact same window ordering:
 * frozen screenshot -> Activity first frame -> FV panel close -> attach the existing interactive
 * CircleSelectOverlay only after the live notification shade is gone.
 */
public final class CircleSelectActivity extends Activity {
    private static final String EXTRA_TOKEN = "circle_token";
    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Map<Long, Payload> PENDING = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SHADE_POLL_MS = 60L;
    private static final long SHADE_WAIT_TIMEOUT_MS = 1_500L;

    private long token;
    private Payload payload;
    private ImageView frozenImage;
    private boolean overlayShown;
    private boolean finished;
    private long waitStartedAt;

    static boolean show(Context context, Bitmap image,
                        FvSystemPanelController.CaptureState shadeState) {
        if (context == null || image == null || image.isRecycled() || shadeState == null) return false;
        Context app = context.getApplicationContext();
        long token = NEXT.getAndIncrement();
        PENDING.put(token, new Payload(image, shadeState));

        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                app, shadeState, "circle_activity_first_frame");
        Intent intent = new Intent(app, CircleSelectActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            app.startActivity(intent);
            DiagnosticLog.i(app, "CIRCLE_ACTIVITY", "START token=" + token
                    + " shadeExpanded=" + shadeState.expandedAtCapture());
            return true;
        } catch (Throwable t) {
            PENDING.remove(token);
            ResultReadyCoordinator.cancel(ticket, app, "circle_activity_start_failed");
            DiagnosticLog.i(app, "CIRCLE_ACTIVITY", "START_FAILED token=" + token + " error=" + t);
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        token = getIntent() == null ? 0L : getIntent().getLongExtra(EXTRA_TOKEN, 0L);
        payload = PENDING.get(token);
        if (payload == null || payload.image == null || payload.image.isRecycled()) {
            finishNoAnim();
            return;
        }

        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        frozenImage = new ImageView(this);
        frozenImage.setImageBitmap(payload.image);
        frozenImage.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(frozenImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        setContentView(root);
        overridePendingTransition(0, 0);

        waitStartedAt = SystemClock.uptimeMillis();
        View decor = window.getDecorView();
        decor.postOnAnimation(this::waitForLiveShadeThenAttachOverlay);
        DiagnosticLog.i(this, "CIRCLE_ACTIVITY", "CREATED token=" + token
                + " image=" + payload.image.getWidth() + "x" + payload.image.getHeight());
    }

    private void waitForLiveShadeThenAttachOverlay() {
        if (finished || isFinishing() || isDestroyed() || payload == null) return;
        boolean expanded = FvSystemPanelController.notificationShadeExpanded();
        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - waitStartedAt);
        DiagnosticLog.i(this, "CIRCLE_ACTIVITY", "shade wait expanded=" + expanded
                + " elapsedMs=" + elapsed);

        if (!expanded) {
            attachCircleOverlay("shade_collapsed");
            return;
        }
        if (elapsed >= SHADE_WAIT_TIMEOUT_MS) {
            // Never strand the user if a ROM refuses every close mechanism. The Activity-hosted path
            // still remains identical to screenshot; this timeout only keeps Circle Select usable.
            attachCircleOverlay("shade_timeout");
            return;
        }
        MAIN.postDelayed(this::waitForLiveShadeThenAttachOverlay, SHADE_POLL_MS);
    }

    private void attachCircleOverlay(String reason) {
        if (overlayShown || finished || payload == null
                || payload.image == null || payload.image.isRecycled()) return;
        overlayShown = true;

        boolean shown = CircleSelectOverlay.show(this, payload.image,
                () -> runOnUiThread(() -> finishFromOverlay("closed")));
        DiagnosticLog.i(this, "CIRCLE_ACTIVITY", "overlay attach shown=" + shown
                + " reason=" + reason
                + " liveExpanded=" + FvSystemPanelController.notificationShadeExpanded());
        if (!shown) {
            overlayShown = false;
            finishCircle("overlay_failed", true);
        } else if (frozenImage != null) {
            // Overlay now owns/recycles the bitmap when it closes. Drop the Activity ImageView ref.
            frozenImage.setImageDrawable(null);
        }
    }

    private void finishFromOverlay(String reason) {
        // CircleSelectOverlay already recycled the shared bitmap before invoking its close callback.
        finishCircle(reason, false);
    }

    private void finishCircle(String reason, boolean recycleBitmap) {
        if (finished) return;
        finished = true;
        MAIN.removeCallbacksAndMessages(null);
        PENDING.remove(token);

        if (recycleBitmap && payload != null && payload.image != null) {
            try {
                if (!payload.image.isRecycled()) payload.image.recycle();
            } catch (Throwable ignored) {}
        }

        FloatService service = FloatService.get();
        if (service != null) {
            service.setScreenshotHidden(false);
            service.onCircleFinished("circle_select_" + reason);
        }
        DiagnosticLog.i(this, "CIRCLE_ACTIVITY", "FINISH reason=" + reason);
        finishNoAnim();
    }

    @Override public void onBackPressed() {
        if (overlayShown) {
            CircleSelectOverlay.dismissActive("back");
        } else {
            finishCircle("back_before_overlay", true);
        }
    }

    @Override protected void onDestroy() {
        MAIN.removeCallbacksAndMessages(null);
        PENDING.remove(token);
        if (!finished) {
            if (overlayShown) {
                CircleSelectOverlay.dismissActive("activity_destroyed");
            } else {
                FloatService service = FloatService.get();
                if (service != null) {
                    service.setScreenshotHidden(false);
                    service.onCircleFinished("circle_select_activity_destroyed");
                }
                if (payload != null && payload.image != null) {
                    try {
                        if (!payload.image.isRecycled()) payload.image.recycle();
                    } catch (Throwable ignored) {}
                }
            }
        }
        super.onDestroy();
    }

    private void finishNoAnim() {
        if (!isFinishing()) finish();
        overridePendingTransition(0, 0);
    }

    private static final class Payload {
        final Bitmap image;
        final FvSystemPanelController.CaptureState shadeState;

        Payload(Bitmap image, FvSystemPanelController.CaptureState shadeState) {
            this.image = image;
            this.shadeState = shadeState;
        }
    }
}
