package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.text.InputType;
import android.text.Selection;
import android.text.Spannable;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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
    private static final long SELECTION_MENU_DELAY_MS = 180L;
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
        SelectionAwareEditText ocrText = selectableText(app);
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
        installSelectionCallbacks(session);

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

    private static SelectionAwareEditText selectableText(Context app) {
        SelectionAwareEditText tv = new SelectionAwareEditText(app);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setGravity(Gravity.TOP | Gravity.START);
        tv.setSingleLine(false);
        tv.setHorizontallyScrolling(false);
        tv.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tv.setKeyListener(null);
        tv.setCursorVisible(false);
        tv.setShowSoftInputOnFocus(false);
        tv.setTextIsSelectable(true);
        tv.setLongClickable(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setSelectAllOnFocus(false);
        tv.setPadding(dp(app, 8), dp(app, 5), dp(app, 8), dp(app, 5));
        return tv;
    }

    private static void installSelectionCallbacks(OverlaySession session) {
        SelectionAwareEditText tv = session.ocrText;
        tv.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                if (menu != null) menu.clear();
                session.selectionActionMode = mode;
                session.selectionGeneration++;
                tv.post(() -> showSelectionMenu(session));
                DiagnosticLog.i(session.app, "SCREENSHOT_RESULT",
                        "OCR_SELECTION_ACTION start=" + tv.getSelectionStart()
                                + " end=" + tv.getSelectionEnd());
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                if (menu != null) menu.clear();
                return true;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                if (session.selectionActionMode == mode) clearSelection(session);
            }
        });

        tv.setSelectionChangedListener((start, end) -> {
            if (session.detached || session.selectionActionMode == null) return;
            long generation = ++session.selectionGeneration;
            FloatActionMenu.dismiss();
            FloatMenuAnchor.clear();
            tv.postDelayed(() -> {
                if (session.detached || session.selectionActionMode == null
                        || generation != session.selectionGeneration) return;
                showSelectionMenu(session);
            }, SELECTION_MENU_DELAY_MS);
        });
    }

    private static void showSelectionMenu(OverlaySession session) {
        if (session == null || session.detached || session.selectionActionMode == null) return;
        String value = selectedText(session.ocrText).trim();
        if (value.isEmpty()) return;
        Rect anchor = FloatMenuAnchor.forTextSelection(session.ocrText);
        DiagnosticLog.i(session.app, "SCREENSHOT_RESULT", "OCR_SELECTION_MENU chars="
                + value.length() + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));
        FloatActionMenu.showTextAt(session.app, value, () -> selectAll(session), anchor);
    }

    private static void selectAll(OverlaySession session) {
        if (session == null || session.detached) return;
        try {
            CharSequence raw = session.ocrText.getText();
            if (raw instanceof Spannable span && span.length() > 0) {
                Selection.setSelection(span, 0, span.length());
                session.ocrText.post(() -> showSelectionMenu(session));
            }
        } catch (Throwable ignored) {}
    }

    private static String selectedText(EditText tv) {
        if (tv == null || tv.getText() == null) return "";
        int a = tv.getSelectionStart();
        int b = tv.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return "";
        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.min(tv.length(), Math.max(a, b));
        return lo < hi ? tv.getText().subSequence(lo, hi).toString() : "";
    }

    private static void clearSelection(OverlaySession session) {
        if (session == null) return;
        session.selectionActionMode = null;
        session.selectionGeneration++;
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
    }

    private static synchronized void beginInlineOcr(OverlaySession session) {
        if (session == null || session.detached || active != session
                || session.image == null || session.image.isRecycled()) return;

        clearSelection(session);
        long generation = ++session.ocrGeneration;
        session.ocrRunning = true;
        session.ocrButton.setEnabled(false);
        session.ocrButton.setText("识别中…");
        Bitmap image = session.image;
        Rect anchor = session.anchor == null ? null : new Rect(session.anchor);

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

        clearSelection(session);
        session.ocrRunning = false;
        session.ocrButton.setEnabled(true);
        session.ocrButton.setText("重新识别");
        session.title.setText("区域截图 · OCR");

        String value = text == null ? "" : text.trim();
        session.ocrText.setText(value.isEmpty() ? "未识别到文字" : value);

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
                + " selectable=true sameWindow=true pos=center");
    }

    public static synchronized void dismissActive(String reason) {
        OverlaySession session = active;
        if (session != null) detach(session, reason == null ? "dismiss" : reason, true);
    }

    private static synchronized boolean detach(OverlaySession session, String reason, boolean recycle) {
        if (session == null || session.detached) return false;
        session.detached = true;
        session.ocrGeneration++;
        if (active == session) active = null;

        clearSelection(session);
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

    private static final class SelectionAwareEditText extends EditText {
        interface SelectionListener { void onChanged(int start, int end); }
        private SelectionListener listener;
        SelectionAwareEditText(Context context) { super(context); }
        void setSelectionChangedListener(SelectionListener listener) { this.listener = listener; }
        @Override protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            if (listener != null) listener.onChanged(selStart, selEnd);
        }
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
        final SelectionAwareEditText ocrText;
        final Button ocrButton;
        final int initialImageHeight;
        final int windowHeight;
        boolean detached;
        boolean ocrRunning;
        long ocrGeneration;
        ActionMode selectionActionMode;
        long selectionGeneration;

        OverlaySession(Context app, FvOverlayWindowHost host, LinearLayout box,
                       Bitmap image, Rect anchor, WindowManager.LayoutParams windowLayout,
                       TextView title, ImageView imageView, LinearLayout ocrPanel,
                       SelectionAwareEditText ocrText, Button ocrButton,
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
