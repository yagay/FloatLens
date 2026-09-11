package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Dedicated screenshot fallback overlay. OCR is explicit via the result button only. */
public final class ScreenshotResultOverlay {
    private static final int MARGIN_DP = 12;
    private static final int GAP_DP = 10;

    public static void show(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return;
        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Rect usable = usableBounds(app, wm);
        Rect selected = anchor == null ? null : new Rect(anchor);

        int maxW = Math.max(dp(app, 220), usable.width() - dp(app, MARGIN_DP * 2));
        int width = Math.min(dp(app, 410), maxW);
        int maxH = Math.min(dp(app, 430), Math.round(usable.height() * .52f));
        int titleH = dp(app, 38);
        int actionsH = dp(app, 50);
        int horizontalPad = dp(app, 14) * 2;
        int imageW = Math.max(dp(app, 160), width - horizontalPad);
        int imageH = Math.round(imageW * (image.getHeight() / (float) Math.max(1, image.getWidth())));
        imageH = clamp(imageH, dp(app, 90), Math.max(dp(app, 90), maxH - titleH - actionsH - dp(app, 22)));
        int height = Math.min(maxH, titleH + actionsH + imageH + dp(app, 22));

        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(app, 14), dp(app, 8), dp(app, 14), dp(app, 8));
        box.setBackgroundColor(0xF0202124);
        box.setElevation(dp(app, 10));

        TextView title = new TextView(app);
        title.setText("区域截图");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(title, new LinearLayout.LayoutParams(-1, titleH));

        ImageView iv = new ImageView(app);
        iv.setImageBitmap(image);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageShareUtils.attachLongPressShare(app, iv, image);
        box.addView(iv, new LinearLayout.LayoutParams(-1, imageH));

        LinearLayout actions = new LinearLayout(app);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button ocr = button(app, "OCR");
        Button save = button(app, "保存图片");
        Button close = button(app, "关闭");
        actions.addView(ocr, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int[] xy = choosePosition(app, usable, selected, width, height);
        lp.x = xy[0];
        lp.y = xy[1];

        try {
            wm.addView(box, lp);
            DiagnosticLog.i(app, "SCREENSHOT_RESULT", "SHOW size=" + width + "x" + height
                    + " pos=" + lp.x + "," + lp.y
                    + " anchor=" + (selected == null ? "none" : selected.toShortString()));
        } catch (Throwable t) {
            DiagnosticLog.i(app, "SCREENSHOT_RESULT", "add failed=" + t);
            return;
        }

        ocr.setOnClickListener(v -> {
            try { wm.removeView(box); } catch (Throwable ignored) {}
            DiagnosticLog.i(app, "SCREENSHOT_RESULT", "OCR_BUTTON fallbackOverlay");
            OcrEngine.recognize(app, image, selected);
        });
        save.setOnClickListener(v -> ScreenshotController.save(app, image));
        close.setOnClickListener(v -> {
            try { wm.removeView(box); } catch (Throwable ignored) {}
        });
    }

    private static Button button(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
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
        return new Rect(0, 0, c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels);
    }

    private static int[] choosePosition(Context c, Rect usable, Rect anchor, int w, int h) {
        int margin = dp(c, MARGIN_DP), gap = dp(c, GAP_DP);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - h);
        if (anchor == null || anchor.isEmpty() || !Rect.intersects(usable, anchor)) {
            return new int[]{clamp(usable.centerX() - w / 2, minX, maxX),
                    clamp(usable.centerY() - h / 2, minY, maxY)};
        }

        int cx = anchor.centerX(), cy = anchor.centerY();
        int[][] raw = {
                {cx - w / 2, anchor.bottom + gap},
                {cx - w / 2, anchor.top - gap - h},
                {anchor.right + gap, cy - h / 2},
                {anchor.left - gap - w, cy - h / 2}
        };
        long bestOverlap = Long.MAX_VALUE;
        int bestShift = Integer.MAX_VALUE;
        int bx = minX, by = minY;
        for (int[] p : raw) {
            int x = clamp(p[0], minX, maxX);
            int y = clamp(p[1], minY, maxY);
            Rect placed = new Rect(x, y, x + w, y + h);
            Rect overlap = new Rect(placed);
            long area = overlap.intersect(anchor) ? (long) overlap.width() * overlap.height() : 0L;
            int shift = Math.abs(x - p[0]) + Math.abs(y - p[1]);
            if (area < bestOverlap || (area == bestOverlap && shift < bestShift)) {
                bestOverlap = area;
                bestShift = shift;
                bx = x;
                by = y;
            }
        }
        return new int[]{bx, by};
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private ScreenshotResultOverlay() {}
}
