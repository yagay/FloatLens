package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

final class GoogleRegionConfirmOverlay {
    interface ConfirmAction { void onConfirm(); }

    private static Context app;
    private static String token = "";
    private static Rect selection;
    private static ConfirmAction action;
    private static FlOverlayWindowHost host;
    private static TextView button;
    private static WindowManager.LayoutParams lp;

    private static final OverlayRegistry.Owner OVERLAY_OWNER = new OverlayRegistry.Owner() {
        @Override public void onAccessibilityHostChanged(boolean available) {
            rebuild("accessibility_host_" + (available ? "available" : "lost"));
        }

        @Override public void onDisplayGeometryChanged() {
            reposition("display_geometry_changed");
        }
    };

    static synchronized void show(
            Context context, String sessionToken, Rect bounds, ConfirmAction confirmAction) {
        if (context == null || sessionToken == null || sessionToken.isBlank()
                || bounds == null || bounds.isEmpty() || confirmAction == null) return;

        app = context.getApplicationContext();
        token = sessionToken;
        selection = new Rect(bounds);
        action = confirmAction;

        if (button == null || host == null || lp == null) createLocked();
        else updatePositionLocked("selection_update");
        OverlayRegistry.register("google_region_confirm", OVERLAY_OWNER);
    }

    static synchronized void dismiss(String sessionToken, String reason) {
        if (button == null && token.isBlank()) return;
        if (sessionToken != null && !sessionToken.isBlank() && !sessionToken.equals(token)) return;
        removeWindowLocked(reason == null ? "dismiss" : reason);
        app = null;
        token = "";
        selection = null;
        action = null;
        OverlayRegistry.unregister("google_region_confirm", OVERLAY_OWNER);
    }

    private static void createLocked() {
        if (app == null || selection == null || action == null) return;
        host = new FlOverlayWindowHost(app);

        TextView view = new TextView(app);
        view.setText("完成");
        view.setTextColor(Color.WHITE);
        view.setTextSize(15f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(false);
        view.setContentDescription("完成区域选择");
        view.setElevation(ScreenGeometry.dp(app, 8f));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(45, 45, 48));
        background.setCornerRadius(ScreenGeometry.dp(app, 18f));
        background.setStroke(ScreenGeometry.dp(app, 1f), Color.argb(70, 255, 255, 255));
        view.setBackground(background);

        int width = ScreenGeometry.dp(app, 76f);
        int height = ScreenGeometry.dp(app, 42f);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
            params.setFitInsetsIgnoringVisibility(true);
        }
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        button = view;
        lp = params;
        updatePositionLocked("initial");

        view.setOnClickListener(v -> {
            ConfirmAction callback;
            String currentToken;
            synchronized (GoogleRegionConfirmOverlay.class) {
                callback = action;
                currentToken = token;
                if (button != v || callback == null || currentToken.isBlank()) return;
                v.setEnabled(false);
            }
            try {
                callback.onConfirm();
            } catch (Throwable t) {
                synchronized (GoogleRegionConfirmOverlay.class) {
                    if (button == v) v.setEnabled(true);
                }
                if (app != null) DiagnosticLog.i(app, "GOOGLE_REGION",
                        "confirm callback failed=" + t);
            }
        });

        if (!host.add(view, params, "google_region_confirm")) {
            button = null;
            lp = null;
            host = null;
            DiagnosticLog.i(app, "GOOGLE_REGION", "confirm overlay add failed");
            return;
        }
        DiagnosticLog.i(app, "GOOGLE_REGION",
                "confirm overlay shown session=" + shortToken(token)
                        + " selection=" + selection);
    }

    private static void updatePositionLocked(String reason) {
        if (app == null || selection == null || lp == null) return;
        Rect display = ScreenGeometry.displayBounds(app);
        Rect usable = ScreenGeometry.usableBounds(app);
        if (display.isEmpty() || usable.isEmpty()) return;

        GoogleRegionConfirmPositioner.Placement placement =
                GoogleRegionConfirmPositioner.place(
                        selection.left, selection.top, selection.right, selection.bottom,
                        usable.left, usable.top, usable.right, usable.bottom,
                        lp.width, lp.height, ScreenGeometry.dp(app, 10f));
        lp.x = placement.left - display.left;
        lp.y = placement.top - display.top;
        if (button != null && host != null && button.getParent() != null) {
            host.update(button, lp, "google_region_confirm_" + reason);
        }
    }

    private static void reposition(String reason) {
        synchronized (GoogleRegionConfirmOverlay.class) {
            updatePositionLocked(reason);
        }
    }

    private static void rebuild(String reason) {
        synchronized (GoogleRegionConfirmOverlay.class) {
            if (app == null || token.isBlank() || selection == null || action == null) return;
            removeWindowLocked(reason);
            createLocked();
        }
    }

    private static void removeWindowLocked(String reason) {
        TextView old = button;
        FlOverlayWindowHost oldHost = host;
        button = null;
        host = null;
        lp = null;
        if (old != null && oldHost != null) {
            oldHost.remove(old, "google_region_confirm_" + reason);
        }
        if (app != null) DiagnosticLog.i(app, "GOOGLE_REGION",
                "confirm overlay removed session=" + shortToken(token)
                        + " reason=" + reason);
    }

    private static String shortToken(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.substring(0, Math.min(8, value.length()));
    }

    private GoogleRegionConfirmOverlay() {}
}
