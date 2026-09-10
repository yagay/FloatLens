package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compact result popup: fixed header/actions, scrollable middle body. */
public final class ResultOverlay {
    private static final int OUTER_MARGIN_DP = 12;
    private static final int ANCHOR_GAP_DP = 10;
    private static final int BOX_HPAD_DP = 16;
    private static final int TITLE_AREA_DP = 34;
    private static final int ACTION_AREA_DP = 50;
    private static final int ROOT_VPAD_DP = 18;
    private static final int BODY_GAP_DP = 6;

    public static void show(Context c, String text, List<String> blocks, Bitmap image) {
        show(c, text, blocks, image, null);
    }

    public static void show(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Rect usable = usableBounds(app, wm);

        int popupW = preferredWidth(app, usable, text, blocks, image);
        int innerW = Math.max(dp(app, 120), popupW - dp(app, BOX_HPAD_DP * 2));
        int maxPopupH = compactMaxHeight(app, usable);
        int reservedH = dp(app, TITLE_AREA_DP + ACTION_AREA_DP + ROOT_VPAD_DP + BODY_GAP_DP);
        int maxBodyH = Math.max(dp(app, 72), maxPopupH - reservedH);

        LinearLayout box = baseBox(app);
        box.addView(title(app, "OCR 结果"), new LinearLayout.LayoutParams(-1, dp(app, TITLE_AREA_DP)));

        ScrollView bodyScroll = new ScrollView(app);
        bodyScroll.setFillViewport(false);
        bodyScroll.setVerticalScrollBarEnabled(true);
        bodyScroll.setScrollbarFadingEnabled(false);
        LinearLayout body = new LinearLayout(app);
        body.setOrientation(LinearLayout.VERTICAL);

        int desiredBodyH = 0;
        if (fs.ocrShowImage() && image != null) {
            int imageMaxH = Math.min(dp(app, 135), Math.max(dp(app, 72), Math.round(usable.height() * .20f)));
            desiredBodyH += addImage(app, body, image, innerW, imageMaxH) + dp(app, 4);
        }

        if (fs.ocrShowText()) {
            // Use a read-only EditText instead of TextView.setTextIsSelectable(true). In overlay
            // windows EditText gives much more reliable character-level handles, long-press
            // selection and selection ActionMode. Keyboard/editing stay disabled.
            EditText all = selectableText(app, text);
            body.addView(all, new LinearLayout.LayoutParams(-1, -2));
            desiredBodyH += textHeight(app, text, innerW - dp(app, 16), 16f, 14);

            if (!fs.ocrCollapse() && blocks != null && blocks.size() > 1) {
                TextView h = new TextView(app);
                h.setText("识别块（长按可精确选择）");
                h.setTextColor(0xFFBBBBBB);
                h.setTextSize(13);
                h.setPadding(dp(app, 8), dp(app, 8), dp(app, 8), dp(app, 3));
                body.addView(h);
                desiredBodyH += dp(app, 31);
                for (String block : blocks) {
                    // Do not install a click-to-copy listener here. It competes with long-press and
                    // handle dragging in the same text area. Explicit Copy All remains below.
                    EditText tv = selectableText(app, block);
                    body.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                    desiredBodyH += textHeight(app, block, innerW - dp(app, 16), 16f, 10);
                }
            }
        }

        if (desiredBodyH <= 0) {
            body.addView(candidate(app, "无可显示内容", false));
            desiredBodyH = dp(app, 48);
        }

        bodyScroll.addView(body);
        int bodyH = clamp(desiredBodyH, dp(app, 56), maxBodyH);
        box.addView(bodyScroll, new LinearLayout.LayoutParams(-1, bodyH));

        LinearLayout actions = actionRow(app);
        Button copy = actionButton(app, "复制全部");
        Button close = actionButton(app, "关闭");
        actions.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, dp(app, ACTION_AREA_DP)));

        int popupH = clamp(reservedH + bodyH, dp(app, 158), maxPopupH);
        showWindow(app, wm, box, popupW, popupH, anchor);
        copy.setOnClickListener(v -> copy(app, text));
        close.setOnClickListener(v -> {
            closeWindow(wm, box);
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished("result_closed");
        });
    }

    public static void showVisual(Context c, Bitmap image, ViewNodeCandidate view) {
        Rect anchor = view == null ? null : new Rect(view.bounds());
        showVisual(c, image, view, anchor);
    }

    public static void showVisual(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Rect usable = usableBounds(app, wm);

        StringBuilder meta = new StringBuilder();
        if (view != null) {
            meta.append(view.label());
            if (!view.className().isBlank()) meta.append("\n").append(view.className());
            if (!view.viewId().isBlank()) meta.append("\n").append(view.viewId());
            meta.append("\n").append(view.bounds().toShortString());
        } else meta.append("图片 View");

        int popupW = preferredWidth(app, usable, meta.toString(), null, image);
        int innerW = Math.max(dp(app, 120), popupW - dp(app, BOX_HPAD_DP * 2));
        int maxPopupH = compactMaxHeight(app, usable);
        int reservedH = dp(app, TITLE_AREA_DP + ACTION_AREA_DP + ROOT_VPAD_DP + BODY_GAP_DP);
        int maxBodyH = Math.max(dp(app, 72), maxPopupH - reservedH);

        LinearLayout box = baseBox(app);
        box.addView(title(app, "View / 图标"), new LinearLayout.LayoutParams(-1, dp(app, TITLE_AREA_DP)));

        ScrollView bodyScroll = new ScrollView(app);
        bodyScroll.setFillViewport(false);
        bodyScroll.setVerticalScrollBarEnabled(true);
        bodyScroll.setScrollbarFadingEnabled(false);
        LinearLayout body = new LinearLayout(app);
        body.setOrientation(LinearLayout.VERTICAL);

        int desiredBodyH = 0;
        if (image != null) {
            int imageMaxH = Math.min(dp(app, 190), Math.max(dp(app, 90), Math.round(usable.height() * .28f)));
            desiredBodyH += addImage(app, body, image, innerW, imageMaxH) + dp(app, 4);
        }
        TextView info = candidate(app, meta.toString(), true);
        body.addView(info, new LinearLayout.LayoutParams(-1, -2));
        desiredBodyH += textHeight(app, meta.toString(), innerW - dp(app, 16), 16f, 14);

        bodyScroll.addView(body);
        int bodyH = clamp(desiredBodyH, dp(app, 64), maxBodyH);
        box.addView(bodyScroll, new LinearLayout.LayoutParams(-1, bodyH));

        LinearLayout actions = actionRow(app);
        Button save = actionButton(app, "保存图片");
        Button close = actionButton(app, "关闭");
        actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, dp(app, ACTION_AREA_DP)));

        int popupH = clamp(reservedH + bodyH, dp(app, 168), maxPopupH);
        showWindow(app, wm, box, popupW, popupH, anchor);
        save.setOnClickListener(v -> { if (image != null) ScreenshotController.save(app, image); });
        close.setOnClickListener(v -> closeWindow(wm, box));
    }

    private static LinearLayout baseBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, BOX_HPAD_DP), dp(c, 9), dp(c, BOX_HPAD_DP), dp(c, 9));
        box.setBackgroundColor(0xF0202124);
        box.setElevation(dp(c, 10));
        return box;
    }

    private static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private static LinearLayout actionRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 3), 0, 0);
        return row;
    }

    private static Button actionButton(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        return b;
    }

    private static int addImage(Context c, LinearLayout body, Bitmap image, int innerW, int maxHeightPx) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return 0;
        float ratio = image.getHeight() / (float) image.getWidth();
        int h = clamp(Math.round(innerW * ratio), dp(c, 44), maxHeightPx);
        ImageView iv = new ImageView(c);
        iv.setImageBitmap(image);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(iv, new LinearLayout.LayoutParams(-1, h));
        return h;
    }

    private static int compactMaxHeight(Context c, Rect usable) {
        int byScreen = Math.round(usable.height() * .52f);
        int hardCap = dp(c, 430);
        int available = Math.max(dp(c, 170), usable.height() - dp(c, OUTER_MARGIN_DP * 2));
        return Math.min(available, Math.max(dp(c, 190), Math.min(byScreen, hardCap)));
    }

    private static int preferredWidth(Context c, Rect usable, String text, List<String> blocks, Bitmap image) {
        int margin = dp(c, OUTER_MARGIN_DP);
        int minW = Math.min(dp(c, 220), Math.max(1, usable.width() - margin * 2));
        int maxW = Math.max(minW, Math.min(dp(c, 410), Math.max(1, usable.width() - margin * 2)));
        int desired = minW;
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setTextSize(spPx(c, 16f));
        desired = Math.max(desired, measuredTextWidth(text, p) + dp(c, BOX_HPAD_DP * 2 + 16));
        if (blocks != null) for (String block : blocks)
            desired = Math.max(desired, measuredTextWidth(block, p) + dp(c, BOX_HPAD_DP * 2 + 16));
        if (image != null && image.getWidth() > 0) {
            float density = Math.max(.1f, c.getResources().getDisplayMetrics().density);
            int sourceDpWidth = Math.round(image.getWidth() / density);
            desired = Math.max(desired, dp(c, Math.min(360, Math.max(170, sourceDpWidth))) + dp(c, BOX_HPAD_DP * 2));
        }
        return clamp(desired, minW, maxW);
    }

    private static int measuredTextWidth(String text, TextPaint p) {
        if (text == null || text.isEmpty()) return 0;
        float max = 0f;
        for (String line : text.split("\\n", -1)) max = Math.max(max, p.measureText(line));
        return Math.round(max);
    }

    private static int wrappedLines(Context c, String text, int widthPx, float textSp) {
        if (text == null || text.isBlank() || widthPx <= 0) return 1;
        try {
            TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            p.setTextSize(spPx(c, textSp));
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), p, widthPx)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            return Math.max(1, layout.getLineCount());
        } catch (Throwable ignored) {
            return Math.max(1, text.split("\\n", -1).length);
        }
    }

    private static int textHeight(Context c, String text, int widthPx, float textSp, int extraDp) {
        return wrappedLines(c, text, widthPx, textSp) * dp(c, 22) + dp(c, extraDp);
    }

    private static void showWindow(Context c, WindowManager wm, LinearLayout box, int popupW, int popupH, Rect anchor) {
        Rect usable = usableBounds(c, wm);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(popupW, popupH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        Rect normalizedAnchor = normalizeAnchor(anchor, usable);
        int[] xy = choosePosition(c, usable, normalizedAnchor, popupW, popupH);
        lp.x = xy[0]; lp.y = xy[1];
        try {
            wm.addView(box, lp);
            DiagnosticLog.i(c, "RESULT_WINDOW", "size=" + popupW + "x" + popupH + " pos=" + lp.x + "," + lp.y
                    + " anchor=" + (normalizedAnchor == null ? "none" : normalizedAnchor.toShortString())
                    + " usable=" + usable.toShortString());
        } catch (Throwable t) {
            Toast.makeText(c, "结果窗口显示失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static int[] choosePosition(Context c, Rect usable, Rect anchor, int w, int h) {
        int margin = dp(c, OUTER_MARGIN_DP), gap = dp(c, ANCHOR_GAP_DP);
        if (anchor == null || anchor.isEmpty()) return new int[]{
                clamp(usable.centerX() - w / 2, usable.left + margin, usable.right - margin - w),
                clamp(usable.centerY() - h / 2, usable.top + margin, usable.bottom - margin - h)};

        int cx = anchor.centerX(), cy = anchor.centerY();
        ArrayList<Placement> choices = new ArrayList<>();
        choices.add(new Placement(cx - w / 2, anchor.bottom + gap, 0));
        choices.add(new Placement(cx - w / 2, anchor.top - gap - h, 1));
        choices.add(new Placement(anchor.right + gap, cy - h / 2, 2));
        choices.add(new Placement(anchor.left - gap - w, cy - h / 2, 3));

        int minX = usable.left + margin, maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin, maxY = Math.max(minY, usable.bottom - margin - h);
        for (Placement p : choices) {
            p.fit = p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY;
            p.cx = clamp(p.x, minX, maxX); p.cy = clamp(p.y, minY, maxY);
            Rect placed = new Rect(p.cx, p.cy, p.cx + w, p.cy + h), overlap = new Rect(placed);
            p.overlap = overlap.intersect(anchor) ? (long) overlap.width() * overlap.height() : 0L;
            p.shift = Math.abs(p.cx - p.x) + Math.abs(p.cy - p.y);
        }
        choices.sort(Comparator.comparing((Placement p) -> !p.fit)
                .thenComparingLong(p -> p.overlap).thenComparingInt(p -> p.shift).thenComparingInt(p -> p.preference));
        Placement best = choices.get(0);
        return new int[]{best.cx, best.cy};
    }

    private static Rect normalizeAnchor(Rect anchor, Rect usable) {
        if (anchor == null || anchor.isEmpty()) return null;
        Rect r = new Rect(anchor);
        return r.intersect(usable) ? r : null;
    }

    private static Rect usableBounds(Context c, WindowManager wm) {
        try {
            var metrics = wm.getCurrentWindowMetrics();
            Rect r = new Rect(metrics.getBounds());
            var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            r.left += insets.left; r.top += insets.top; r.right -= insets.right; r.bottom -= insets.bottom;
            if (!r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        return new Rect(0, 0, c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels);
    }

    private static void closeWindow(WindowManager wm, LinearLayout box) {
        try { wm.removeView(box); } catch (Throwable ignored) {}
    }

    /**
     * Read-only multiline editor used only as a selection surface. EditText's editor machinery gives
     * precise Android selection handles even inside TYPE_APPLICATION_OVERLAY, unlike a selectable
     * TextView nested in a scrolling overlay. No IME or mutation is allowed.
     */
    private static EditText selectableText(Context c, String text) {
        EditText tv = new EditText(c);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16);
        tv.setBackgroundColor(0x00000000);
        tv.setGravity(Gravity.TOP | Gravity.START);
        tv.setSingleLine(false);
        tv.setHorizontallyScrolling(false);
        tv.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tv.setKeyListener(null);
        tv.setCursorVisible(false);
        tv.setShowSoftInputOnFocus(false);
        tv.setTextIsSelectable(true);
        tv.setLongClickable(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setSelectAllOnFocus(false);
        tv.setPadding(dp(c, 8), dp(c, 5), dp(c, 8), dp(c, 5));
        return tv;
    }

    private static TextView candidate(Context c, String text, boolean selectable) {
        TextView tv = new TextView(c);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16);
        tv.setTextIsSelectable(selectable);
        tv.setPadding(dp(c, 8), dp(c, 5), dp(c, 8), dp(c, 5));
        return tv;
    }

    private static void copy(Context c, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("FloatLens OCR", text == null ? "" : text));
        Toast.makeText(c, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static float spPx(Context c, float sp) { return sp * c.getResources().getDisplayMetrics().scaledDensity; }
    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    private static int clamp(int v, int min, int max) { if (max < min) return min; return Math.max(min, Math.min(v, max)); }

    private static final class Placement {
        final int x, y, preference; int cx, cy, shift; long overlap; boolean fit;
        Placement(int x, int y, int preference) { this.x = x; this.y = y; this.preference = preference; }
    }

    private ResultOverlay() {}
}
