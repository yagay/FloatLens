package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.text.InputType;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END = 2;
    private static OverlaySession active;

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
        installManualSelection(session);

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
        tv.setHighlightColor(0x884285F4);
        tv.setGravity(Gravity.TOP | Gravity.START);
        tv.setSingleLine(false);
        tv.setHorizontallyScrolling(false);
        tv.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tv.setKeyListener(null);
        tv.setCursorVisible(false);
        tv.setShowSoftInputOnFocus(false);
        tv.setTextIsSelectable(false);
        tv.setLongClickable(false);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setSelectAllOnFocus(false);
        // Leave enough bottom room for the custom circular handles on the last line.
        tv.setPadding(dp(app, 8), dp(app, 5), dp(app, 8), dp(app, 22));
        return tv;
    }

    private static void installManualSelection(OverlaySession session) {
        SelectionAwareEditText tv = session.ocrText;
        int touchSlop = ViewConfiguration.get(session.app).getScaledTouchSlop();
        int longPressMs = ViewConfiguration.getLongPressTimeout();

        tv.setOnTouchListener((v, event) -> {
            if (session.detached || event == null || tv.length() == 0) return false;
            float x = event.getX();
            float y = event.getY();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    session.touchDown = true;
                    session.manualSelecting = false;
                    session.handleMode = HANDLE_NONE;
                    session.downX = x;
                    session.downY = y;
                    FloatActionMenu.dismiss();
                    FloatMenuAnchor.clear();
                    cancelLongPress(session);

                    if (session.selectionActive) {
                        int handle = tv.hitSelectionHandle(x, y);
                        if (handle != HANDLE_NONE) {
                            session.handleMode = handle;
                            session.manualSelecting = true;
                            requestNoParentIntercept(tv, true);
                            DiagnosticLog.i(session.app, "SCREENSHOT_RESULT",
                                    "OCR_SELECTION_HANDLE_DOWN mode="
                                            + (handle == HANDLE_START ? "start" : "end"));
                            return true;
                        }
                    }

                    session.longPressRunnable = () -> {
                        if (session.detached || !session.touchDown || tv.length() == 0) return;
                        int offset = offsetForTouch(tv, session.downX, session.downY);
                        int[] word = wordBounds(tv.getText(), offset);
                        if (word[1] <= word[0]) return;
                        session.manualSelecting = true;
                        session.selectionActive = true;
                        session.selectionAnchorStart = word[0];
                        session.selectionAnchorEnd = word[1];
                        requestNoParentIntercept(tv, true);
                        applySelection(session, word[0], word[1]);
                        DiagnosticLog.i(session.app, "SCREENSHOT_RESULT",
                                "OCR_SELECTION_BEGIN start=" + word[0] + " end=" + word[1]);
                        tv.post(() -> showSelectionMenu(session));
                    };
                    tv.postDelayed(session.longPressRunnable, longPressMs);
                    return false;
                }

                case MotionEvent.ACTION_MOVE -> {
                    if (session.handleMode != HANDLE_NONE) {
                        requestNoParentIntercept(tv, true);
                        FloatActionMenu.dismiss();
                        FloatMenuAnchor.clear();
                        updateSelectionHandle(session, offsetForTouch(tv, x, y));
                        return true;
                    }
                    if (!session.manualSelecting) {
                        float dx = x - session.downX;
                        float dy = y - session.downY;
                        if (dx * dx + dy * dy > touchSlop * touchSlop) cancelLongPress(session);
                        return false;
                    }
                    requestNoParentIntercept(tv, true);
                    FloatActionMenu.dismiss();
                    FloatMenuAnchor.clear();
                    extendSelectionTo(session, offsetForTouch(tv, x, y));
                    return true;
                }

                case MotionEvent.ACTION_UP -> {
                    session.touchDown = false;
                    cancelLongPress(session);

                    if (session.handleMode != HANDLE_NONE) {
                        updateSelectionHandle(session, offsetForTouch(tv, x, y));
                        int released = session.handleMode;
                        session.handleMode = HANDLE_NONE;
                        session.manualSelecting = false;
                        requestNoParentIntercept(tv, false);
                        DiagnosticLog.i(session.app, "SCREENSHOT_RESULT",
                                "OCR_SELECTION_HANDLE_UP mode="
                                        + (released == HANDLE_START ? "start" : "end")
                                        + " start=" + tv.getSelectionStart()
                                        + " end=" + tv.getSelectionEnd());
                        tv.post(() -> showSelectionMenu(session));
                        return true;
                    }

                    if (!session.manualSelecting) return false;
                    extendSelectionTo(session, offsetForTouch(tv, x, y));
                    session.manualSelecting = false;
                    requestNoParentIntercept(tv, false);
                    DiagnosticLog.i(session.app, "SCREENSHOT_RESULT",
                            "OCR_SELECTION_END start=" + tv.getSelectionStart()
                                    + " end=" + tv.getSelectionEnd());
                    tv.post(() -> showSelectionMenu(session));
                    return true;
                }

                case MotionEvent.ACTION_CANCEL -> {
                    session.touchDown = false;
                    cancelLongPress(session);
                    if (session.manualSelecting || session.handleMode != HANDLE_NONE) {
                        session.handleMode = HANDLE_NONE;
                        session.manualSelecting = false;
                        requestNoParentIntercept(tv, false);
                        tv.post(() -> showSelectionMenu(session));
                        return true;
                    }
                    return false;
                }
            }
            return false;
        });
    }

    private static void updateSelectionHandle(OverlaySession session, int offset) {
        if (session == null || !session.selectionActive || session.handleMode == HANDLE_NONE) return;
        SelectionAwareEditText tv = session.ocrText;
        CharSequence text = tv.getText();
        if (text == null || text.length() == 0) return;
        int a = Math.min(tv.getSelectionStart(), tv.getSelectionEnd());
        int b = Math.max(tv.getSelectionStart(), tv.getSelectionEnd());
        if (a < 0 || b <= a) return;
        int safe = clamp(offset, 0, text.length());
        int probe = safe >= text.length() ? text.length() - 1 : safe;
        int[] word = wordBounds(text, probe);

        if (session.handleMode == HANDLE_START) {
            int nextStart = clamp(word[0], 0, Math.max(0, b - 1));
            applySelection(session, nextStart, b);
        } else {
            int nextEnd = clamp(word[1], Math.min(text.length(), a + 1), text.length());
            applySelection(session, a, nextEnd);
        }
    }

    private static void extendSelectionTo(OverlaySession session, int offset) {
        if (session == null || !session.selectionActive) return;
        CharSequence text = session.ocrText.getText();
        if (text == null || text.length() == 0) return;
        int safe = clamp(offset, 0, text.length());
        int probe = safe >= text.length() ? text.length() - 1 : safe;
        int[] word = wordBounds(text, probe);
        if (safe <= session.selectionAnchorStart) {
            applySelection(session, word[0], session.selectionAnchorEnd);
        } else {
            applySelection(session, session.selectionAnchorStart, word[1]);
        }
    }

    private static void applySelection(OverlaySession session, int start, int end) {
        if (session == null || session.detached) return;
        SelectionAwareEditText tv = session.ocrText;
        CharSequence raw = tv.getText();
        if (!(raw instanceof Spannable span) || span.length() == 0) return;
        int a = clamp(Math.min(start, end), 0, span.length());
        int b = clamp(Math.max(start, end), 0, span.length());
        if (a == b) {
            if (b < span.length()) b++;
            else if (a > 0) a--;
        }
        if (a >= b) return;
        tv.requestFocus();
        Selection.setSelection(span, a, b);
        session.selectionActive = true;
        tv.setHandlesVisible(true);
        tv.invalidate();
    }

    private static int offsetForTouch(EditText tv, float x, float y) {
        if (tv == null || tv.length() == 0) return 0;
        try {
            return clamp(tv.getOffsetForPosition(x, y), 0, tv.length());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int[] wordBounds(CharSequence text, int offset) {
        if (text == null || text.length() == 0) return new int[]{0, 0};
        int n = text.length();
        int p = clamp(offset, 0, n - 1);
        char c = text.charAt(p);

        if (Character.isLowSurrogate(c) && p > 0 && Character.isHighSurrogate(text.charAt(p - 1))) {
            return new int[]{p - 1, Math.min(n, p + 1)};
        }
        if (Character.isHighSurrogate(c) && p + 1 < n && Character.isLowSurrogate(text.charAt(p + 1))) {
            return new int[]{p, p + 2};
        }
        if (isCjk(c) || !isLatinWordChar(c)) return new int[]{p, Math.min(n, p + 1)};

        int left = p;
        int right = p + 1;
        while (left > 0 && isLatinWordChar(text.charAt(left - 1)) && !isCjk(text.charAt(left - 1))) left--;
        while (right < n && isLatinWordChar(text.charAt(right)) && !isCjk(text.charAt(right))) right++;
        return new int[]{left, right};
    }

    private static boolean isLatinWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '’';
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static void requestNoParentIntercept(View view, boolean disallow) {
        try {
            if (view != null && view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        } catch (Throwable ignored) {}
    }

    private static void cancelLongPress(OverlaySession session) {
        if (session == null || session.longPressRunnable == null) return;
        try { session.ocrText.removeCallbacks(session.longPressRunnable); }
        catch (Throwable ignored) {}
        session.longPressRunnable = null;
    }

    private static void showSelectionMenu(OverlaySession session) {
        if (session == null || session.detached || !session.selectionActive) return;
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
                session.selectionActive = true;
                session.selectionAnchorStart = 0;
                session.selectionAnchorEnd = span.length();
                Selection.setSelection(span, 0, span.length());
                session.ocrText.setHandlesVisible(true);
                session.ocrText.invalidate();
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
        cancelLongPress(session);
        session.touchDown = false;
        session.manualSelecting = false;
        session.selectionActive = false;
        session.handleMode = HANDLE_NONE;
        requestNoParentIntercept(session.ocrText, false);
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        session.ocrText.setHandlesVisible(false);
        session.ocrText.invalidate();
        try {
            CharSequence raw = session.ocrText.getText();
            if (raw instanceof Spannable span) {
                int end = span.length();
                Selection.setSelection(span, end, end);
            }
        } catch (Throwable ignored) {}
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
                + " manualSelection=true handles=true sameWindow=true pos=center");
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
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final PointF startHandle = new PointF(Float.NaN, Float.NaN);
        private final PointF endHandle = new PointF(Float.NaN, Float.NaN);
        private boolean handlesVisible;

        SelectionAwareEditText(Context context) {
            super(context);
            handlePaint.setColor(0xFF4285F4);
            handlePaint.setStyle(Paint.Style.FILL);
        }

        void setHandlesVisible(boolean visible) {
            if (handlesVisible == visible) return;
            handlesVisible = visible;
            invalidate();
        }

        int hitSelectionHandle(float x, float y) {
            updateHandlePoints();
            if (!handlesVisible) return HANDLE_NONE;
            float hit = dp(getContext(), 28);
            float hit2 = hit * hit;
            if (!Float.isNaN(startHandle.x)) {
                float dx = x - startHandle.x;
                float dy = y - startHandle.y;
                if (dx * dx + dy * dy <= hit2) return HANDLE_START;
            }
            if (!Float.isNaN(endHandle.x)) {
                float dx = x - endHandle.x;
                float dy = y - endHandle.y;
                if (dx * dx + dy * dy <= hit2) return HANDLE_END;
            }
            return HANDLE_NONE;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!handlesVisible) return;
            updateHandlePoints();
            float stem = dp(getContext(), 7);
            float radius = dp(getContext(), 7);
            if (!Float.isNaN(startHandle.x)) {
                canvas.drawLine(startHandle.x, startHandle.y - stem, startHandle.x, startHandle.y, handlePaint);
                canvas.drawCircle(startHandle.x, startHandle.y, radius, handlePaint);
            }
            if (!Float.isNaN(endHandle.x)) {
                canvas.drawLine(endHandle.x, endHandle.y - stem, endHandle.x, endHandle.y, handlePaint);
                canvas.drawCircle(endHandle.x, endHandle.y, radius, handlePaint);
            }
        }

        private void updateHandlePoints() {
            startHandle.set(Float.NaN, Float.NaN);
            endHandle.set(Float.NaN, Float.NaN);
            if (!handlesVisible || getText() == null || length() == 0) return;
            int a = Math.min(getSelectionStart(), getSelectionEnd());
            int b = Math.max(getSelectionStart(), getSelectionEnd());
            if (a < 0 || b <= a) return;
            Layout layout = getLayout();
            if (layout == null) return;

            int startOffset = clamp(a, 0, Math.max(0, length() - 1));
            int endProbe = clamp(Math.max(a, b - 1), 0, Math.max(0, length() - 1));
            int startLine = layout.getLineForOffset(startOffset);
            int endLine = layout.getLineForOffset(endProbe);
            float startX = getTotalPaddingLeft() + layout.getPrimaryHorizontal(a) - getScrollX();
            float endX = getTotalPaddingLeft() + layout.getPrimaryHorizontal(b) - getScrollX();
            float startY = getTotalPaddingTop() + layout.getLineBottom(startLine) - getScrollY() + dp(getContext(), 7);
            float endY = getTotalPaddingTop() + layout.getLineBottom(endLine) - getScrollY() + dp(getContext(), 7);
            startHandle.set(startX, startY);
            endHandle.set(endX, endY);
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
        boolean touchDown;
        boolean manualSelecting;
        boolean selectionActive;
        int selectionAnchorStart;
        int selectionAnchorEnd;
        int handleMode = HANDLE_NONE;
        float downX;
        float downY;
        Runnable longPressRunnable;

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
