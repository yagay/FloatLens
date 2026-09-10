package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Result popup whose size follows its content and whose position follows the selected screen area. */
public final class ResultOverlay {
    private static final int OUTER_MARGIN_DP = 12;
    private static final int ANCHOR_GAP_DP = 10;
    private static final int BOX_HPAD_DP = 18;

    public static void show(Context c, String text, List<String> blocks, Bitmap image) {
        show(c, text, blocks, image, null);
    }

    /** Show OCR/View text close to, but preferably not covering, the selected screen rectangle. */
    public static void show(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Rect usable = usableBounds(app, wm);

        int popupW = preferredWidth(app, usable, text, blocks, image);
        int innerW = Math.max(dp(app, 120), popupW - dp(app, BOX_HPAD_DP * 2));
        int maxPopupH = Math.max(dp(app, 180), Math.round(usable.height() * .72f));

        LinearLayout box = baseBox(app);
        box.addView(title(app, "OCR 结果"));

        if (fs.ocrShowImage() && image != null) {
            int maxImageH = Math.max(dp(app, 80), Math.round(usable.height() * .30f));
            addImage(app, box, image, innerW, maxImageH);
        }

        if (fs.ocrShowText()) {
            ScrollView sv = new ScrollView(app);
            sv.setFillViewport(false);
            LinearLayout content = new LinearLayout(app);
            content.setOrientation(LinearLayout.VERTICAL);

            TextView all = candidate(app, text, true);
            content.addView(all, new LinearLayout.LayoutParams(innerW, -2));

            int textLines = wrappedLines(app, text, innerW - dp(app, 16), 16f);
            if (!fs.ocrCollapse() && blocks != null && blocks.size() > 1) {
                TextView h = new TextView(app);
                h.setText("识别块（点按复制单块）");
                h.setTextColor(0xFFBBBBBB);
                h.setPadding(0, dp(app, 12), 0, dp(app, 6));
                content.addView(h);
                textLines += 2;
                for (String block : blocks) {
                    TextView tv = candidate(app, block, false);
                    tv.setOnClickListener(v -> copy(app, block));
                    content.addView(tv, new LinearLayout.LayoutParams(innerW, -2));
                    textLines += wrappedLines(app, block, innerW - dp(app, 16), 16f);
                }
            }
            sv.addView(content);

            int lineHeight = dp(app, 23);
            int desiredTextH = textLines * lineHeight + dp(app, fs.ocrCollapse() ? 18 : 28);
            int minTextH = dp(app, 56);
            int maxTextH = Math.max(minTextH, Math.round(usable.height() * (fs.ocrCollapse() ? .30f : .42f)));
            int textH = clamp(desiredTextH, minTextH, maxTextH);
            box.addView(sv, new LinearLayout.LayoutParams(innerW, textH));
        }

        LinearLayout actions = new LinearLayout(app);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(app);
        copy.setText("复制全部");
        Button close = new Button(app);
        close.setText("关闭");
        actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(actions);

        showWindow(app, wm, box, popupW, maxPopupH, anchor);
        copy.setOnClickListener(v -> copy(app, text));
        close.setOnClickListener(v -> {
            closeWindow(wm, box);
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished("result_closed");
        });
    }

    /** Pure ImageView/ImageButton/icon candidate selected from the accessibility View tree. */
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
        } else {
            meta.append("图片 View");
        }

        int popupW = preferredWidth(app, usable, meta.toString(), null, image);
        int innerW = Math.max(dp(app, 120), popupW - dp(app, BOX_HPAD_DP * 2));
        int maxPopupH = Math.max(dp(app, 180), Math.round(usable.height() * .72f));

        LinearLayout box = baseBox(app);
        box.addView(title(app, "View / 图标"));
        if (image != null) {
            addImage(app, box, image, innerW, Math.max(dp(app, 100), Math.round(usable.height() * .40f)));
        }

        TextView info = candidate(app, meta.toString(), true);
        box.addView(info, new LinearLayout.LayoutParams(innerW, -2));

        LinearLayout actions = new LinearLayout(app);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(app);
        save.setText("保存图片");
        Button close = new Button(app);
        close.setText("关闭");
        actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(actions);

        showWindow(app, wm, box, popupW, maxPopupH, anchor);
        save.setOnClickListener(v -> { if (image != null) ScreenshotController.save(app, image); });
        close.setOnClickListener(v -> closeWindow(wm, box));
    }

    private static LinearLayout baseBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, BOX_HPAD_DP), dp(c, 14), dp(c, BOX_HPAD_DP), dp(c, 10));
        box.setBackgroundColor(0xF0202124);
        box.setElevation(dp(c, 10));
        return box;
    }

    private static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(c, 6));
        return t;
    }

    /** Preserve image aspect ratio and let narrow/small crops produce a smaller popup. */
    private static void addImage(Context c, LinearLayout box, Bitmap image, int innerW, int maxHeightPx) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;
        float ratio = image.getHeight() / (float) image.getWidth();
        int h = Math.round(innerW * ratio);
        h = clamp(h, dp(c, 48), maxHeightPx);
        ImageView iv = new ImageView(c);
        iv.setImageBitmap(image);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new LinearLayout.LayoutParams(innerW, h));
    }

    private static int preferredWidth(Context c, Rect usable, String text, List<String> blocks, Bitmap image) {
        int margin = dp(c, OUTER_MARGIN_DP);
        int minW = Math.min(Math.max(dp(c, 210), usable.width() / 2), Math.max(1, usable.width() - margin * 2));
        int maxW = Math.max(minW, Math.min(dp(c, 430), Math.max(1, usable.width() - margin * 2)));
        int desired = minW;

        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setTextSize(spPx(c, 16f));
        desired = Math.max(desired, measuredTextWidth(text, p) + dp(c, BOX_HPAD_DP * 2 + 18));
        if (blocks != null) {
            for (String block : blocks) {
                desired = Math.max(desired, measuredTextWidth(block, p) + dp(c, BOX_HPAD_DP * 2 + 18));
            }
        }
        if (image != null && image.getWidth() > 0) {
            float density = Math.max(.1f, c.getResources().getDisplayMetrics().density);
            int sourceDpWidth = Math.round(image.getWidth() / density);
            desired = Math.max(desired, dp(c, Math.min(390, Math.max(180, sourceDpWidth))) + dp(c, BOX_HPAD_DP * 2));
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
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
            return Math.max(1, layout.getLineCount());
        } catch (Throwable ignored) {
            return Math.max(1, text.split("\\n", -1).length);
        }
    }

    private static void showWindow(Context c, WindowManager wm, LinearLayout box,
                                   int popupW, int maxPopupH, Rect anchor) {
        Rect usable = usableBounds(c, wm);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(maxPopupH, View.MeasureSpec.AT_MOST);
        box.measure(widthSpec, heightSpec);
        int popupH = clamp(box.getMeasuredHeight(), dp(c, 100), maxPopupH);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                popupW,
                popupH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        Rect normalizedAnchor = normalizeAnchor(anchor, usable);
        int[] xy = choosePosition(c, usable, normalizedAnchor, popupW, popupH);
        lp.x = xy[0];
        lp.y = xy[1];

        try {
            wm.addView(box, lp);
            DiagnosticLog.i(c, "RESULT_WINDOW", "size=" + popupW + "x" + popupH
                    + " pos=" + lp.x + "," + lp.y
                    + " anchor=" + (normalizedAnchor == null ? "none" : normalizedAnchor.toShortString())
                    + " usable=" + usable.toShortString());
        } catch (Throwable t) {
            Toast.makeText(c, "结果窗口显示失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Pick the best of below / above / right / left. A fitting position wins; otherwise choose the
     * candidate with the least overlap/overflow after clamping. This keeps the result close to the
     * selected content without covering it whenever the screen has enough room.
     */
    private static int[] choosePosition(Context c, Rect usable, Rect anchor, int w, int h) {
        int margin = dp(c, OUTER_MARGIN_DP);
        int gap = dp(c, ANCHOR_GAP_DP);
        if (anchor == null || anchor.isEmpty()) {
            return new int[]{
                    clamp(usable.centerX() - w / 2, usable.left + margin, usable.right - margin - w),
                    clamp(usable.centerY() - h / 2, usable.top + margin, usable.bottom - margin - h)
            };
        }

        int cx = anchor.centerX();
        int cy = anchor.centerY();
        ArrayList<Placement> choices = new ArrayList<>();
        choices.add(new Placement(cx - w / 2, anchor.bottom + gap, 0));       // below
        choices.add(new Placement(cx - w / 2, anchor.top - gap - h, 1));      // above
        choices.add(new Placement(anchor.right + gap, cy - h / 2, 2));        // right
        choices.add(new Placement(anchor.left - gap - w, cy - h / 2, 3));     // left

        final int minX = usable.left + margin;
        final int maxX = Math.max(minX, usable.right - margin - w);
        final int minY = usable.top + margin;
        final int maxY = Math.max(minY, usable.bottom - margin - h);

        for (Placement p : choices) {
            p.fit = p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY;
            p.cx = clamp(p.x, minX, maxX);
            p.cy = clamp(p.y, minY, maxY);
            Rect placed = new Rect(p.cx, p.cy, p.cx + w, p.cy + h);
            Rect overlap = new Rect(placed);
            p.overlap = overlap.intersect(anchor) ? (long) overlap.width() * overlap.height() : 0L;
            p.shift = Math.abs(p.cx - p.x) + Math.abs(p.cy - p.y);
        }

        choices.sort(Comparator
                .comparing((Placement p) -> !p.fit)
                .thenComparingLong(p -> p.overlap)
                .thenComparingInt(p -> p.shift)
                .thenComparingInt(p -> p.preference));
        Placement best = choices.get(0);
        return new int[]{best.cx, best.cy};
    }

    private static Rect normalizeAnchor(Rect anchor, Rect usable) {
        if (anchor == null || anchor.isEmpty()) return null;
        Rect r = new Rect(anchor);
        if (!r.intersect(usable)) return null;
        return r;
    }

    private static Rect usableBounds(Context c, WindowManager wm) {
        try {
            var metrics = wm.getCurrentWindowMetrics();
            Rect r = new Rect(metrics.getBounds());
            var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
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

    private static void closeWindow(WindowManager wm, LinearLayout box) {
        try { wm.removeView(box); } catch (Throwable ignored) {}
    }

    private static TextView candidate(Context c, String text, boolean selectable) {
        TextView tv = new TextView(c);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(16);
        tv.setTextIsSelectable(selectable);
        tv.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));
        return tv;
    }

    private static void copy(Context c, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("FloatLens OCR", text == null ? "" : text));
        Toast.makeText(c, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static float spPx(Context c, float sp) {
        return sp * c.getResources().getDisplayMetrics().scaledDensity;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private static final class Placement {
        final int x, y, preference;
        int cx, cy, shift;
        long overlap;
        boolean fit;
        Placement(int x, int y, int preference) {
            this.x = x;
            this.y = y;
            this.preference = preference;
        }
    }

    private ResultOverlay() {}
}
