package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** FV-style screenshot result surface hosted above SystemUI whenever accessibility is available. */
public final class ScreenshotResultOverlay {
    private static final int MARGIN_DP = 12;
    private static final int TITLE_H_DP = 38;
    private static final int ACTION_H_DP = 50;
    private static final long OCR_INLINE_TIMEOUT_MS = 12_000L;
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
        int titleH = dp(app, TITLE_H_DP);
        int actionsH = dp(app, ACTION_H_DP);
        int horizontalPad = dp(app, 14) * 2;
        int imageW = Math.max(dp(app, 160), width - horizontalPad);
        int imageH = Math.round(imageW * (image.getHeight() / (float) Math.max(1, image.getWidth())));
        imageH = clamp(imageH, dp(app, 90),
                Math.max(dp(app, 90), maxH - titleH - actionsH - dp(app, 22)));
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
        iv.setAdjustViewBounds(false);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageShareUtils.attachLongPressShare(app, iv, image);
        box.addView(iv, new LinearLayout.LayoutParams(-1, imageH));

        // OCR stays inside the same 2032 result window. It starts hidden, then shares the fixed
        // content budget with the screenshot image after recognition completes.
        LinearLayout ocrPanel = new LinearLayout(app);
        ocrPanel.setOrientation(LinearLayout.VERTICAL);
        ocrPanel.setVisibility(View.GONE);
        ocrPanel.setPadding(0, dp(app, 4), 0, dp(app, 4));

        TextView ocrHeading = new TextView(app);
        ocrHeading.setText("OCR 文字");
        ocrHeading.setTextColor(0xFFBBBBBB);
        ocrHeading.setTextSize(13);
        ocrHeading.setGravity(Gravity.CENTER_VERTICAL);
        ocrPanel.addView(ocrHeading, new LinearLayout.LayoutParams(-1, dp(app, 26)));

        ScrollView ocrScroll = new ScrollView(app);
        ocrScroll.setFillViewport(false);
        ocrScroll.setVerticalScrollBarEnabled(true);
        ocrScroll.setScrollbarFadingEnabled(false);
        TextView ocrText = new TextView(app);
        ocrText.setTextColor(Color.WHITE);
        ocrText.setTextSize(16);
        ocrText.setGravity(Gravity.TOP | Gravity.START);
        ocrText.setPadding(dp(app, 8), dp(app, 5), dp(app, 8), dp(app, 5));
        ocrText.setTextIsSelectable(true);
        ocrText.setLongClickable(true);
        ocrText.setFocusable(true);
        ocrText.setFocusableInTouchMode(true);
        ocrScroll.addView(ocrText, new ScrollView.LayoutParams(-1, -2));
        ocrPanel.addView(ocrScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        box.addView(ocrPanel, new LinearLayout.LayoutParams(-1, 0));

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
        // Keep the result surface in one stable place. The anchor is retained for OCR source
        // semantics only; it never moves the popup.
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;

        if (!host.add(box, lp, "screenshot_result")) {
            DiagnosticLog.i(app, "SCREENSHOT_RESULT", "add failed all hosts");
            return false;
        }

        OverlaySession session = new OverlaySession(app, host, box, image, selected,
                lp, title, iv, ocrPanel, ocrText, ocr, imageH, height);
        active = session;
        DiagnosticLog.i(app, "SCREENSHOT_RESULT", "SHOW size=" + width + "x" + height
                + " pos=center"
                + " anchor=" + (selected == null ? "none" : selected.toShortString())
                + " accessibilityHost=" + host.isAccessibilityHosted()
                + " type=" + lp.type);

        ocr.setOnClickListener(v -> beginInlineOcr(session));
        save.setOnClickListener(v -> ScreenshotController.save(app, image));
        close.setOnClickListener(v -> detach(session, "close", true));
        return true;
    }

    private static synchronized void beginInlineOcr(OverlaySession session) {
        if (session == null || session.detached || active != session
                || session.image == null || session.image.isRecycled()) return;

        long generation = ++session.ocrGeneration;
        session.ocrRunning = true;
        session.ocrButton.setEnabled(false);
        session.ocrButton.setText("识别中…");
        Bitmap image = session.image;
        Rect anchor = session.anchor == null ? null : new Rect(session.anchor);

        // OcrEngine ultimately calls ResultTextActivity.show(). This one-shot sink is consumed by
        // ResultActivity.showOcr() before any Activity is launched, so the current accessibility
        // overlay receives the OCR result and remains the only visible window.
        ResultTextActivity.captureNextForImage(image, (text, blocks) -> session.box.post(() ->
                showInlineOcr(session, generation, text, blocks)));

        session.ocrButton.postDelayed(() -> {
            synchronized (ScreenshotResultOverlay.class) {
                if (session.detached || active != session || !session.ocrRunning
                        || session.ocrGeneration != generation) return;
                ResultTextActivity.clearInlineForImage(image);
                session.ocrRunning = false;
                session.ocrButton.setEnabled(true);
                session.ocrButton.setText("OCR");
                DiagnosticLog.i(session.app, "SCREENSHOT_RESULT",
                        "OCR_INLINE_TIMEOUT generation=" + generation);
            }
        }, OCR_INLINE_TIMEOUT_MS);

        DiagnosticLog.i(session.app, "SCREENSHOT_RESULT", "OCR_INLINE_BEGIN generation="
                + generation + " accessibilityOverlay=" + session.host.isAccessibilityHosted());
        OcrEngine.recognize(session.app, image, anchor);
    }

    private static synchronized void showInlineOcr(OverlaySession session, long generation,
                                                   String text, List<String> blocks) {
        if (session == null || session.detached || active != session
                || generation != session.ocrGeneration) return;

        session.ocrRunning = false;
        session.ocrButton.setEnabled(true);
        session.ocrButton.setText("重新识别");
        session.title.setText("区域截图 · OCR");

        String value = text == null ? "" : text.trim();
        session.ocrText.setText(value.isEmpty() ? "未识别到文字" : value);

        // OCR text selection needs a focusable window. Shade cleanup has already been started as soon
        // as the screenshot result appeared, so it is safe to promote focus when the user explicitly
        // requests OCR.
        if ((session.windowLayout.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            session.windowLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            boolean updated = session.host.update(session.box, session.windowLayout,
                    "screenshot_result_ocr_focus");
            DiagnosticLog.i(session.app, "SCREENSHOT_RESULT",
                    "OCR_INLINE_FOCUS updated=" + updated);
        }

        int titleH = dp(session.app, TITLE_H_DP);
        int actionsH = dp(session.app, ACTION_H_DP);
        int contentBudget = Math.max(dp(session.app, 100),
                session.windowHeight - titleH - actionsH - dp(session.app, 22));
        int panelH = clamp(contentBudget / 2, dp(session.app, 96),
                Math.min(dp(session.app, 190), contentBudget));
        int newImageH = Math.max(0, contentBudget - panelH);

        session.imageView.setLayoutParams(new LinearLayout.LayoutParams(-1, newImageH));
        session.imageView.setVisibility(newImageH > 0 ? View.VISIBLE : View.GONE);
        session.ocrPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, panelH));
        session.ocrPanel.setVisibility(View.VISIBLE);
        session.ocrText.requestFocus();

        DiagnosticLog.i(session.app, "SCREENSHOT_RESULT", "OCR_INLINE_SHOW chars="
                + value.length() + " blocks=" + (blocks == null ? 0 : blocks.size())
                + " imageH=" + newImageH + " panelH=" + panelH
                + " sameWindow=true pos=center");
    }

    public static synchronized void dismissActive(String reason) {
        OverlaySession session = active;
        if (session != null) detach(session, reason == null ? "dismiss" : reason, true);
    }

    /** Remove one result surface and cancel any inline OCR that still targets it. */
    private static synchronized boolean detach(OverlaySession session, String reason, boolean recycle) {
        if (session == null || session.detached) return false;
        session.detached = true;
        session.ocrGeneration++;
        if (active == session) active = null;

        if (session.ocrRunning) {
            ResultTextActivity.clearInlineForImage(session.image);
            OcrEngine.invalidatePending(session.app, "screenshot_result_" + reason);
            session.ocrRunning = false;
        }

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
        final WindowManager.LayoutParams windowLayout;
        final TextView title;
        final ImageView imageView;
        final LinearLayout ocrPanel;
        final TextView ocrText;
        final Button ocrButton;
        final int initialImageHeight;
        final int windowHeight;
        boolean detached;
        boolean ocrRunning;
        long ocrGeneration;

        OverlaySession(Context app, FvOverlayWindowHost host, LinearLayout box,
                       Bitmap image, Rect anchor, WindowManager.LayoutParams windowLayout,
                       TextView title, ImageView imageView, LinearLayout ocrPanel,
                       TextView ocrText, Button ocrButton,
                       int initialImageHeight, int windowHeight) {
            this.app = app;
            this.host = host;
            this.box = box;
            this.image = image;
            this.anchor = anchor == null ? null : new Rect(anchor);
            this.windowLayout = windowLayout;
            this.title = title;
            this.imageView = imageView;
            this.ocrPanel = ocrPanel;
            this.ocrText = ocrText;
            this.ocrButton = ocrButton;
            this.initialImageHeight = initialImageHeight;
            this.windowHeight = windowHeight;
        }
    }

    private ScreenshotResultOverlay() {}
}
