package com.yagay.floatlens;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Lightweight image long-press menu that also works from TYPE_APPLICATION_OVERLAY windows. */
public final class ImageActionMenu {
    private static WindowManager activeWm;
    private static View activeView;

    public static synchronized void show(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return;
        dismiss();

        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        Palette palette = Palette.from(app);
        LinearLayout root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4));
        root.setBackground(rounded(palette.surface, dp(app, 18)));
        root.setElevation(dp(app, 10));
        root.setClipToOutline(true);
        root.setClickable(true);

        TextView share = row(app, "分享图片", palette);
        TextView save = row(app, "保存图片", palette);
        root.addView(share, new LinearLayout.LayoutParams(-1, dp(app, 48)));
        root.addView(save, new LinearLayout.LayoutParams(-1, dp(app, 48)));

        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        Rect usable = usableBounds(app, wm);
        int width = Math.min(dp(app, 176), Math.max(dp(app, 132), usable.width() - dp(app, 16)));
        int height = dp(app, 104);
        int[] pos = menuPosition(app, usable, anchor, width, height);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = pos[0];
        lp.y = pos[1];

        try {
            wm.addView(root, lp);
            activeWm = wm;
            activeView = root;
            DiagnosticLog.i(app, "IMAGE_ACTION_MENU", "SHOW anchor="
                    + (anchor == null ? "none" : anchor.toShortString())
                    + " pos=" + lp.x + "," + lp.y);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "IMAGE_ACTION_MENU", "SHOW_FAILED " + t);
            return;
        }

        share.setOnClickListener(v -> {
            dismiss();
            ImageShareUtils.share(app, image);
        });
        save.setOnClickListener(v -> {
            dismiss();
            ScreenshotController.save(app, image);
        });
    }

    public static synchronized void dismiss() {
        View v = activeView;
        WindowManager wm = activeWm;
        activeView = null;
        activeWm = null;
        if (v != null && wm != null) {
            try { wm.removeView(v); } catch (Throwable ignored) {}
        }
    }

    private static TextView row(Context c, String text, Palette palette) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(palette.primaryText);
        tv.setTextSize(14);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        tv.setBackground(ripple(palette.ripple));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private static int[] menuPosition(Context c, Rect usable, Rect anchor, int w, int h) {
        int margin = dp(c, 8);
        int gap = dp(c, 8);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - h);

        if (anchor == null || anchor.isEmpty()) {
            return new int[]{
                    clamp(usable.centerX() - w / 2, minX, maxX),
                    clamp(usable.centerY() - h / 2, minY, maxY)};
        }

        int x = clamp(anchor.centerX() - w / 2, minX, maxX);
        int above = anchor.top - gap - h;
        int below = anchor.bottom + gap;
        int y;
        if (above >= minY) y = above;
        else if (below <= maxY) y = below;
        else {
            int roomAbove = Math.max(0, anchor.top - minY);
            int roomBelow = Math.max(0, usable.bottom - margin - anchor.bottom);
            y = roomBelow >= roomAbove
                    ? clamp(below, minY, maxY)
                    : clamp(above, minY, maxY);
        }
        return new int[]{x, y};
    }

    private static Rect usableBounds(Context c, WindowManager wm) {
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

    private static android.graphics.drawable.Drawable rounded(int color, float radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private static android.graphics.drawable.Drawable ripple(int color) {
        ColorStateList ripple = ColorStateList.valueOf(color);
        GradientDrawable content = new GradientDrawable();
        content.setColor(Color.TRANSPARENT);
        content.setCornerRadius(999f);
        return new RippleDrawable(ripple, content, null);
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static final class Palette {
        final int surface;
        final int primaryText;
        final int ripple;

        Palette(int surface, int primaryText, int ripple) {
            this.surface = surface;
            this.primaryText = primaryText;
            this.ripple = ripple;
        }

        static Palette from(Context c) {
            int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean dark = night == Configuration.UI_MODE_NIGHT_YES;
            return dark
                    ? new Palette(0xFF2B2B2B, 0xFFF5F5F5, 0x33FFFFFF)
                    : new Palette(0xFFF8F8F8, 0xFF202124, 0x22000000);
        }
    }

    private ImageActionMenu() {}
}
