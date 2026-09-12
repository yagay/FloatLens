package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared geometry and widget helpers for every result surface. */
final class ResultUi {
    static final int OUTER_MARGIN_DP = 12;
    static final int BOX_HPAD_DP = 14;
    static final int TITLE_H_DP = 38;
    static final int ACTION_H_DP = 50;
    static final int ROOT_VPAD_DP = 16;
    /** One radius for Screenshot / View / OCR result popups. */
    static final int POPUP_RADIUS_DP = 18;

    static LinearLayout box(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, BOX_HPAD_DP), dp(c, 8), dp(c, BOX_HPAD_DP), dp(c, 8));
        box.setBackground(popupBackground(c, 0xF0202124));
        box.setElevation(dp(c, 10));
        // The Dialog window itself is transparent, so clipping the one shared result card gives
        // Screenshot / View / OCR exactly the same visible rounded outline.
        box.setClipToOutline(true);
        return box;
    }

    static android.graphics.drawable.Drawable popupBackground(Context c, int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(c, POPUP_RADIUS_DP));
        return background;
    }

    static TextView heading(Context c, String text) {
        TextView title = new TextView(c);
        title.setText(text == null ? "" : text);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER_VERTICAL);
        return title;
    }

    static LinearLayout actionRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static Button button(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        return b;
    }

    static Rect usableBounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        try {
            var metrics = wm.getCurrentWindowMetrics();
            Rect r = new Rect(metrics.getBounds());
            var insets = metrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            r.left += insets.left;
            r.top += insets.top;
            r.right -= insets.right;
            r.bottom -= insets.bottom;
            if (!r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        return new Rect(0, 0,
                c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels);
    }

    static int standardWidth(Context c, Rect usable) {
        int margin = dp(c, OUTER_MARGIN_DP);
        int available = Math.max(1, usable.width() - margin * 2);
        return Math.min(dp(c, 410), Math.max(dp(c, 220), available));
    }

    static int standardMaxHeight(Context c, Rect usable) {
        int byScreen = Math.round(usable.height() * .52f);
        int hardCap = dp(c, 430);
        int available = Math.max(dp(c, 170), usable.height() - dp(c, OUTER_MARGIN_DP * 2));
        return Math.min(available, Math.max(dp(c, 190), Math.min(byScreen, hardCap)));
    }

    static int imageHeight(Context c, Bitmap image, int width, int maxHeight) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return 0;
        int innerWidth = Math.max(dp(c, 120), width - dp(c, BOX_HPAD_DP * 2));
        int h = Math.round(innerWidth * (image.getHeight() / (float) image.getWidth()));
        return clamp(h, dp(c, 72), Math.max(dp(c, 72), maxHeight));
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private ResultUi() {}
}
