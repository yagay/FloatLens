package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct Accessibility View-content result. This is intentionally separate from OCR results so the
 * UI never labels extracted Accessibility text as OCR.
 */
public final class ViewContentActivity extends AppCompatActivity {
    private static final String EXTRA_TOKEN = "view_result_token";
    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Map<Long, Payload> PENDING = new ConcurrentHashMap<>();
    private static final int PROCESS_GROUP = 0x56430010;
    private static final int PROCESS_BASE = 0x56431000;

    private long token;
    private Payload payload;

    public static boolean show(Context c, String text, Bitmap image, Rect anchor) {
        if (c == null || text == null || text.isBlank()) return false;
        long token = NEXT.getAndIncrement();
        PENDING.put(token, new Payload(text, image, anchor));
        Intent i = new Intent(c, ViewContentActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            c.startActivity(i);
            DiagnosticLog.i(c, "VIEW_CONTENT", "START token=" + token + " chars=" + text.length());
            return true;
        } catch (Throwable t) {
            PENDING.remove(token);
            DiagnosticLog.i(c, "VIEW_CONTENT", "START_FAILED " + t);
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        token = getIntent().getLongExtra(EXTRA_TOKEN, 0L);
        payload = PENDING.get(token);
        if (payload == null) {
            finish();
            return;
        }

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        // Anchor/usable bounds are screen coordinates, so use the full-screen WindowManager coordinate space.
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setFinishOnTouchOutside(true);
        buildUi();
        overridePendingTransition(0, 0);
        DiagnosticLog.i(this, "VIEW_CONTENT", "CREATED token=" + token);
    }

    private void buildUi() {
        Rect usable = usableBounds();
        int maxWidth = Math.max(dp(220), usable.width() - dp(24));
        int width = Math.min(dp(410), maxWidth);
        int maxHeight = Math.min(dp(430), Math.round(usable.height() * .52f));
        int titleH = dp(38), actionH = dp(50);
        int bodyMax = Math.max(dp(80), maxHeight - titleH - actionH - dp(18));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(8));
        box.setBackgroundColor(0xF0202124);
        box.setElevation(dp(10));

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("View 内容");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(title, new LinearLayout.LayoutParams(-1, titleH));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int desired = 0;

        if (payload.image != null && !payload.image.isRecycled()) {
            int innerW = width - dp(28);
            int imageH = Math.round(innerW * (payload.image.getHeight()
                    / (float) Math.max(1, payload.image.getWidth())));
            imageH = clamp(imageH, dp(60), dp(130));
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(payload.image);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ImageShareUtils.attachLongPressShare(this, iv, payload.image);
            body.addView(iv, new LinearLayout.LayoutParams(-1, imageH));
            desired += imageH + dp(4);
        }

        EditText text = selectableText(payload.text);
        body.addView(text, new LinearLayout.LayoutParams(-1, -2));
        int approxLines = Math.max(1, payload.text.split("\\n", -1).length
                + payload.text.length() / 28);
        desired += clamp(approxLines * dp(22) + dp(16), dp(60), dp(280));

        scroll.addView(body);
        int bodyH = clamp(desired, dp(70), bodyMax);
        // Only the middle body may shrink/scroll. Keep the bottom action row pinned and visible.
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("复制全部");
        Button close = button("关闭");
        actions.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionH));

        setContentView(box);
        int height = Math.min(maxHeight, titleH + actionH + bodyH + dp(18));
        positionWindow(usable, width, height, payload.anchor);
        DiagnosticLog.i(this, "RESULT_LAYOUT", "VIEW pinnedActions=true requestedBody=" + bodyH
                + " popupH=" + height);

        copy.setOnClickListener(v -> copy(payload.text));
        close.setOnClickListener(v -> finishNoAnim());
    }

    private EditText selectableText(String value) {
        EditText tv = new EditText(this);
        tv.setText(value);
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
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        tv.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                refreshProcessItems(menu, tv);
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                refreshProcessItems(menu, tv);
                return true;
            }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                Intent target = item.getIntent();
                if (target == null || !Intent.ACTION_PROCESS_TEXT.equals(target.getAction())) return false;
                String selected = selectedText(tv);
                if (selected.isEmpty()) return false;
                try {
                    startActivity(new Intent(target)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true));
                } catch (Throwable t) {
                    Toast.makeText(ViewContentActivity.this, "无法打开文本处理应用", Toast.LENGTH_SHORT).show();
                }
                mode.finish();
                return true;
            }
            @Override public void onDestroyActionMode(ActionMode mode) {}
        });
        return tv;
    }

    private void refreshProcessItems(Menu menu, EditText tv) {
        try { menu.removeGroup(PROCESS_GROUP); } catch (Throwable ignored) {}
        String value = selectedText(tv);
        if (value.isEmpty()) return;

        Intent base = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, value)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        List<ResolveInfo> handlers;
        try { handlers = getPackageManager().queryIntentActivities(base, PackageManager.MATCH_ALL); }
        catch (Throwable t) { handlers = new ArrayList<>(); }
        if (handlers == null) return;

        Set<String> existing = new HashSet<>();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Intent old = item == null ? null : item.getIntent();
            if (old != null && Intent.ACTION_PROCESS_TEXT.equals(old.getAction()) && old.getComponent() != null) {
                existing.add(old.getComponent().flattenToString());
            }
        }
        int id = PROCESS_BASE, order = 20;
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : handlers) {
            if (ri == null || ri.activityInfo == null) continue;
            String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
            if (!existing.add(key)) continue;
            CharSequence label;
            try { label = ri.loadLabel(pm); } catch (Throwable ignored) { label = ri.activityInfo.name; }
            Intent target = new Intent(base).setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            MenuItem item = menu.add(PROCESS_GROUP, id++, order++, label == null ? ri.activityInfo.name : label);
            item.setIntent(target);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
    }

    private String selectedText(EditText tv) {
        if (tv == null || tv.getText() == null) return "";
        int a = tv.getSelectionStart(), b = tv.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return "";
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return tv.getText().subSequence(lo, hi).toString();
    }

    private void positionWindow(Rect usable, int width, int height, Rect anchor) {
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = width;
        lp.height = height;
        lp.gravity = Gravity.TOP | Gravity.START;
        int[] xy = choosePosition(usable, anchor, width, height);
        lp.x = xy[0]; lp.y = xy[1];
        w.setAttributes(lp);
        DiagnosticLog.i(this, "VIEW_CONTENT", "WINDOW size=" + width + "x" + height
                + " pos=" + lp.x + "," + lp.y
                + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));
    }

    private int[] choosePosition(Rect usable, Rect anchor, int w, int h) {
        int margin = dp(12), gap = dp(10);
        int minX = usable.left + margin, maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin, maxY = Math.max(minY, usable.bottom - margin - h);
        if (anchor == null || anchor.isEmpty() || !Rect.intersects(usable, anchor)) {
            return new int[]{clamp(usable.centerX() - w / 2, minX, maxX),
                    clamp(usable.centerY() - h / 2, minY, maxY)};
        }
        int cx = anchor.centerX(), cy = anchor.centerY();
        int[][] choices = {{cx - w / 2, anchor.bottom + gap}, {cx - w / 2, anchor.top - gap - h},
                {anchor.right + gap, cy - h / 2}, {anchor.left - gap - w, cy - h / 2}};
        long bestArea = Long.MAX_VALUE; int bestShift = Integer.MAX_VALUE, bx = minX, by = minY;
        for (int[] p : choices) {
            int x = clamp(p[0], minX, maxX), y = clamp(p[1], minY, maxY);
            Rect placed = new Rect(x, y, x + w, y + h); Rect overlap = new Rect(placed);
            long area = overlap.intersect(anchor) ? (long) overlap.width() * overlap.height() : 0L;
            int shift = Math.abs(x - p[0]) + Math.abs(y - p[1]);
            if (area < bestArea || (area == bestArea && shift < bestShift)) {
                bestArea = area; bestShift = shift; bx = x; by = y;
            }
        }
        return new int[]{bx, by};
    }

    private Rect usableBounds() {
        try {
            var metrics = getWindowManager().getMaximumWindowMetrics();
            Rect r = new Rect(metrics.getBounds());
            var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            r.left += insets.left; r.top += insets.top; r.right -= insets.right; r.bottom -= insets.bottom;
            if (!r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        return new Rect(0, 0, getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(14); b.setMinHeight(0); b.setMinimumHeight(0);
        return b;
    }

    private void copy(String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("FloatLens View", value));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private void finishNoAnim() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onBackPressed() { finishNoAnim(); }

    @Override protected void onDestroy() {
        if (token != 0L) PENDING.remove(token);
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private static final class Payload {
        final String text;
        final Bitmap image;
        final Rect anchor;
        Payload(String text, Bitmap image, Rect anchor) {
            this.text = text;
            this.image = image;
            this.anchor = anchor == null ? null : new Rect(anchor);
        }
    }
}
