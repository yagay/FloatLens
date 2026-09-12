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

/** FV-style screenshot result surface hosted above SystemUI whenever accessibility is available. */
public final class ScreenshotResultOverlay {
    private static final int MARGIN_DP = 12;
    private static OverlaySession active;

    /**
     * Shows the screenshot result immediately. Returns false only when neither accessibility nor
     * application overlay hosting could attach the result surface; caller may then use Activity fallback.
     */
    public static synchronized boolean show(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        dismissActive("replace");

        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        FvOverlayWindowHost host = new FvOverlayWindowHost(app);
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
        // Keep the result surface in one stable place. The anchor is still retained for OCR, but it
        // no longer changes the popup location from one capture to the next.
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;

        if (!host.add(box, lp, "screenshot_result")) {
            DiagnosticLog.i(app, "SCREENSHOT_RESULT", "add failed all hosts");
            return false;
        }

        OverlaySession session = new OverlaySession(app, host, box, image, selected);
        active = session;
        DiagnosticLog.i(app, "SCREENSHOT_RESULT", "SHOW size=" + width + "x" + height
                + " pos=center"
                + " anchor=" + (selected == null ? "none" : selected.toShortString())
                + " accessibilityHost=" + host.isAccessibilityHosted()
                + " type=" + lp.type);

        ocr.setOnClickListener(v -> {
            if (!detach(session, "ocr", false)) return;
            DiagnosticLog.i(app, "SCREENSHOT_RESULT", "OCR_BUTTON accessibilityOverlay="
                    + host.isAccessibilityHosted());
            OcrEngine.recognize(app, image, selected);
        });
        save.setOnClickListener(v -> ScreenshotController.save(app, image));
        close.setOnClickListener(v -> detach(session, "close", true));
        return true;
    }

    public static synchronized void dismissActive(String reason) {
        OverlaySession session = active;
        if (session != null) detach(session, reason == null ? "dismiss" : reason, true);
    }

    /** Remove one result surface. recycle=false transfers Bitmap ownership to the next operation. */
    private static synchronized boolean detach(OverlaySession session, String reason, boolean recycle) {
        if (session == null || session.detached) return false;
        session.detached = true;
        if (active == session) active = null;
        session.host.remove(session.box, "screenshot_result");
        if (recycle) {
            try {
                if (!session.image.isRecycled()) session.image.recycle();
            } catch (Throwable ignored) {}
        }
        DiagnosticLog.i(session.app, "SCREENSHOT_RESULT", "DETACH reason=" + reason
                + " recycle=" + recycle);
        return true;
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

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private static final class OverlaySession {
        final Context app;
        final FvOverlayWindowHost host;
        final LinearLayout box;
        final Bitmap image;
        final Rect anchor;
        boolean detached;

        OverlaySession(Context app, FvOverlayWindowHost host, LinearLayout box,
                       Bitmap image, Rect anchor) {
            this.app = app;
            this.host = host;
            this.box = box;
            this.image = image;
            this.anchor = anchor == null ? null : new Rect(anchor);
        }
    }

    private ScreenshotResultOverlay() {}
}
