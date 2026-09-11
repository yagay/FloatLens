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
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** OCR text result hosted by a real Activity window so native selection ActionMode works. */
public final class ResultTextActivity extends AppCompatActivity {
    private static final String EXTRA_TOKEN = "result_token";
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final Map<Long, Payload> PENDING = new ConcurrentHashMap<>();

    private static final int OUTER_MARGIN_DP = 12;
    private static final int ANCHOR_GAP_DP = 10;
    private static final int BOX_HPAD_DP = 16;
    private static final int TITLE_AREA_DP = 34;
    private static final int ACTION_AREA_DP = 50;
    private static final int ROOT_VPAD_DP = 18;
    private static final int BODY_GAP_DP = 6;

    private static final int MENU_COPY = 0x46540001;
    private static final int MENU_SHARE = 0x46540002;
    private static final int MENU_GROUP_PROCESS = 0x46540100;
    private static final int MENU_PROCESS_BASE = 0x46541000;

    private long token;
    private Payload payload;
    private boolean circleFinished;
    private ActionMode activeBlockActionMode;

    public interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    private static final Object INLINE_LOCK = new Object();
    private static Bitmap inlineImage;
    private static InlineResultSink inlineSink;

    public static void captureNextForImage(Bitmap image, InlineResultSink sink) {
        synchronized (INLINE_LOCK) {
            inlineImage = image;
            inlineSink = sink;
        }
    }

    public static void clearInlineForImage(Bitmap image) {
        synchronized (INLINE_LOCK) {
            if (inlineImage == image) {
                inlineImage = null;
                inlineSink = null;
            }
        }
    }

    private static InlineResultSink takeInlineSink(Bitmap image) {
        synchronized (INLINE_LOCK) {
            if (inlineSink == null || inlineImage != image) return null;
            InlineResultSink sink = inlineSink;
            inlineImage = null;
            inlineSink = null;
            return sink;
        }
    }

    public static boolean show(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        if (c == null) return false;
        InlineResultSink inline = takeInlineSink(image);
        if (inline != null) {
            try {
                String value = text == null ? "" : text;
                List<String> safeBlocks = blocks == null ? List.of() : new ArrayList<>(blocks);
                inline.onResult(value, safeBlocks);
                DiagnosticLog.i(c, "RESULT_TEXT_ACTIVITY", "INLINE chars=" + value.length()
                        + " blocks=" + safeBlocks.size());
                return true;
            } catch (Throwable t) {
                DiagnosticLog.i(c, "RESULT_TEXT_ACTIVITY", "INLINE_FAILED " + t);
            }
        }
        long token = NEXT_TOKEN.getAndIncrement();
        Payload p = new Payload(text, blocks, image, anchor);
        PENDING.put(token, p);
        Intent i = new Intent(c, ResultTextActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            c.startActivity(i);
            DiagnosticLog.i(c, "RESULT_TEXT_ACTIVITY", "START token=" + token
                    + " chars=" + p.text.length() + " blocks=" + p.blocks.size());
            return true;
        } catch (Throwable t) {
            PENDING.remove(token);
            DiagnosticLog.i(c, "RESULT_TEXT_ACTIVITY", "START_FAILED " + t);
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
        // PositionWindow uses absolute screen coordinates; keep the Activity window in the same coordinate space.
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setFinishOnTouchOutside(true);

        buildUi();
        overridePendingTransition(0, 0);
        DiagnosticLog.i(this, "RESULT_TEXT_ACTIVITY", "CREATED token=" + token);
    }

    private void buildUi() {
        FloatSettings fs = new FloatSettings(this);
        Rect usable = usableBounds();
        int popupW = preferredWidth(usable, payload.text, payload.blocks, payload.image);
        int innerW = Math.max(dp(120), popupW - dp(BOX_HPAD_DP * 2));
        int maxPopupH = compactMaxHeight(usable);
        int reservedH = dp(TITLE_AREA_DP + ACTION_AREA_DP + ROOT_VPAD_DP + BODY_GAP_DP);
        int maxBodyH = Math.max(dp(72), maxPopupH - reservedH);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(BOX_HPAD_DP), dp(9), dp(BOX_HPAD_DP), dp(9));
        box.setBackgroundColor(0xF0202124);
        box.setElevation(dp(10));

        TextView heading = new TextView(this);
        heading.setText("OCR 结果");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(17);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(heading, new LinearLayout.LayoutParams(-1, dp(TITLE_AREA_DP)));

        ScrollView bodyScroll = new ScrollView(this);
        bodyScroll.setFillViewport(false);
        bodyScroll.setVerticalScrollBarEnabled(true);
        bodyScroll.setScrollbarFadingEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int desiredBodyH = 0;

        if (fs.ocrShowImage() && payload.image != null) {
            int imageMaxH = Math.min(dp(135), Math.max(dp(72), Math.round(usable.height() * .20f)));
            desiredBodyH += addImage(body, payload.image, innerW, imageMaxH) + dp(4);
        }

        if (fs.ocrShowText()) {
            EditText all = selectableText(payload.text);
            body.addView(all, new LinearLayout.LayoutParams(-1, -2));
            desiredBodyH += textHeight(payload.text, innerW - dp(16), 16f, 14);

            if (!fs.ocrCollapse() && payload.blocks.size() > 1) {
                TextView h = new TextView(this);
                h.setText("识别块（点按文本处理）");
                h.setTextColor(0xFFBBBBBB);
                h.setTextSize(13);
                h.setPadding(dp(8), dp(8), dp(8), dp(3));
                body.addView(h);
                desiredBodyH += dp(31);

                for (String block : payload.blocks) {
                    TextView tv = blockView(block);
                    tv.setOnClickListener(v -> showBlockActionMode(tv, block));
                    body.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                    desiredBodyH += textHeight(block, innerW - dp(16), 16f, 10);
                }
            }
        }

        if (desiredBodyH <= 0) {
            TextView empty = blockView("无可显示内容");
            body.addView(empty);
            desiredBodyH = dp(48);
        }

        bodyScroll.addView(body);
        int bodyH = clamp(desiredBodyH, dp(56), maxBodyH);
        // The body is the only flexible area. Header/actions always keep their fixed height even
        // when Dialog decor or OEM insets reduce the real content viewport.
        box.addView(bodyScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(3), 0, 0);
        Button copy = actionButton("复制全部");
        Button close = actionButton("关闭");
        actions.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, dp(ACTION_AREA_DP)));

        setContentView(box);
        int popupH = clamp(reservedH + bodyH, dp(158), maxPopupH);
        positionWindow(usable, popupW, popupH, payload.anchor);
        DiagnosticLog.i(this, "RESULT_LAYOUT", "OCR pinnedActions=true requestedBody=" + bodyH
                + " popupH=" + popupH);

        copy.setOnClickListener(v -> copyText(payload.text));
        close.setOnClickListener(v -> finishWithCircle("result_closed"));
    }

    /**
     * Full OCR text keeps the native selection handles and framework actions. We append every
     * visible ACTION_PROCESS_TEXT handler because some OEM frameworks only expose a shortened list.
     */
    private EditText selectableText(String text) {
        EditText tv = new EditText(this);
        tv.setText(text == null ? "" : text);
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
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));

        tv.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                refreshFullSelectionProcessItems(menu, tv);
                DiagnosticLog.i(ResultTextActivity.this, "RESULT_TEXT_MENU",
                        "FULL_CREATE selected=" + selectedText(tv).length()
                                + " items=" + menu.size());
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                refreshFullSelectionProcessItems(menu, tv);
                return true;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                Intent target = item.getIntent();
                if (target != null && Intent.ACTION_PROCESS_TEXT.equals(target.getAction())) {
                    String selected = selectedText(tv);
                    if (selected.isEmpty()) return false;
                    Intent current = new Intent(target)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
                    try {
                        startActivity(current);
                    } catch (Throwable t) {
                        Toast.makeText(ResultTextActivity.this,
                                "无法打开文本处理应用", Toast.LENGTH_SHORT).show();
                    }
                    mode.finish();
                    return true;
                }
                // Copy / Select all / framework actions stay owned by TextView's native Editor.
                return false;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                DiagnosticLog.i(ResultTextActivity.this, "RESULT_TEXT_MENU", "FULL_DESTROY");
            }
        });
        return tv;
    }

    private String selectedText(EditText tv) {
        if (tv == null || tv.getText() == null) return "";
        int start = tv.getSelectionStart();
        int end = tv.getSelectionEnd();
        if (start < 0 || end < 0 || start == end) return "";
        int lo = Math.max(0, Math.min(start, end));
        int hi = Math.min(tv.length(), Math.max(start, end));
        return lo < hi ? tv.getText().subSequence(lo, hi).toString() : "";
    }

    private void refreshFullSelectionProcessItems(Menu menu, EditText tv) {
        if (menu == null) return;
        try { menu.removeGroup(MENU_GROUP_PROCESS); } catch (Throwable ignored) {}
        String selected = selectedText(tv);
        if (!selected.isEmpty()) addProcessTextItems(menu, selected);
    }

    private TextView blockView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setClickable(true);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        return tv;
    }

    /** Block tap: the whole OCR block is passed to a floating system ActionMode. */
    private void showBlockActionMode(TextView anchor, String block) {
        final String value = block == null ? "" : block.trim();
        if (value.isEmpty()) return;

        if (activeBlockActionMode != null) {
            try { activeBlockActionMode.finish(); } catch (Throwable ignored) {}
            activeBlockActionMode = null;
        }

        ActionMode.Callback2 cb = new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, MENU_COPY, 0, "复制")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(Menu.NONE, MENU_SHARE, 1, "分享")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                addProcessTextItems(menu, value);
                DiagnosticLog.i(ResultTextActivity.this, "RESULT_TEXT_MENU",
                        "CREATE block chars=" + value.length() + " items=" + menu.size());
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == MENU_COPY) {
                    copyText(value);
                    mode.finish();
                    return true;
                }
                if (item.getItemId() == MENU_SHARE) {
                    shareText(value);
                    mode.finish();
                    return true;
                }
                Intent target = item.getIntent();
                if (target != null) {
                    try { startActivity(target); }
                    catch (Throwable t) {
                        Toast.makeText(ResultTextActivity.this,
                                "无法打开文本处理应用", Toast.LENGTH_SHORT).show();
                    }
                    mode.finish();
                    return true;
                }
                return false;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                if (activeBlockActionMode == mode) activeBlockActionMode = null;
                DiagnosticLog.i(ResultTextActivity.this, "RESULT_TEXT_MENU", "BLOCK_DESTROY");
            }

            @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                outRect.set(0, 0, Math.max(1, anchor.getWidth()), Math.max(1, anchor.getHeight()));
            }
        };

        ActionMode mode = null;
        try { mode = anchor.startActionMode(cb, ActionMode.TYPE_FLOATING); }
        catch (Throwable t) {
            DiagnosticLog.i(this, "RESULT_TEXT_MENU", "BLOCK_ACTIONMODE_FAILED " + t);
        }
        if (mode == null) {
            DiagnosticLog.i(this, "RESULT_TEXT_MENU", "BLOCK_ACTIONMODE_NULL -> chooser");
            launchProcessTextChooser(value);
        } else {
            activeBlockActionMode = mode;
        }
    }

    /** Add all visible PROCESS_TEXT handlers, while avoiding duplicates already inserted by Android. */
    private void addProcessTextItems(Menu menu, String value) {
        if (menu == null || value == null || value.isBlank()) return;
        Intent base = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, value)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> handlers;
        try { handlers = pm.queryIntentActivities(base, PackageManager.MATCH_ALL); }
        catch (Throwable t) { handlers = new ArrayList<>(); }

        Set<String> existing = new HashSet<>();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem old = menu.getItem(i);
            Intent oi = old == null ? null : old.getIntent();
            if (oi == null || !Intent.ACTION_PROCESS_TEXT.equals(oi.getAction())
                    || oi.getComponent() == null) continue;
            existing.add(oi.getComponent().flattenToString());
        }

        int order = 10;
        int id = MENU_PROCESS_BASE;
        if (handlers == null) return;
        for (ResolveInfo ri : handlers) {
            if (ri == null || ri.activityInfo == null) continue;
            String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
            if (!existing.add(key)) continue;
            CharSequence label;
            try { label = ri.loadLabel(pm); }
            catch (Throwable ignored) { label = ri.activityInfo.name; }
            Intent target = new Intent(base)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            MenuItem item = menu.add(MENU_GROUP_PROCESS, id++, order++,
                    label == null ? ri.activityInfo.name : label);
            item.setIntent(target);
            // Force these into overflow so every handler remains reachable from the floating toolbar.
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
    }

    private void launchProcessTextChooser(String value) {
        Intent process = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, value)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        try { startActivity(Intent.createChooser(process, "处理文字")); }
        catch (Throwable t) {
            Toast.makeText(this, "没有可用的文本处理应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String value) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, value);
        try { startActivity(Intent.createChooser(share, "分享文字")); }
        catch (Throwable t) {
            Toast.makeText(this, "无法打开分享菜单", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyText(String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("FloatLens OCR", value == null ? "" : value));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    /** A new tap in the result Activity dismisses the current block ActionMode first. */
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN
                && activeBlockActionMode != null) {
            ActionMode old = activeBlockActionMode;
            activeBlockActionMode = null;
            try { old.finish(); } catch (Throwable ignored) {}
            DiagnosticLog.i(this, "RESULT_TEXT_MENU", "BLOCK_DISMISS_BY_TOUCH");
        }
        return super.dispatchTouchEvent(ev);
    }

    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private int addImage(LinearLayout body, Bitmap image, int innerW, int maxHeightPx) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return 0;
        float ratio = image.getHeight() / (float) image.getWidth();
        int h = clamp(Math.round(innerW * ratio), dp(44), maxHeightPx);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(image);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(iv, new LinearLayout.LayoutParams(-1, h));
        return h;
    }

    private int compactMaxHeight(Rect usable) {
        int byScreen = Math.round(usable.height() * .52f);
        int hardCap = dp(430);
        int available = Math.max(dp(170), usable.height() - dp(OUTER_MARGIN_DP * 2));
        return Math.min(available, Math.max(dp(190), Math.min(byScreen, hardCap)));
    }

    private int preferredWidth(Rect usable, String text, List<String> blocks, Bitmap image) {
        int margin = dp(OUTER_MARGIN_DP);
        int minW = Math.min(dp(220), Math.max(1, usable.width() - margin * 2));
        int maxW = Math.max(minW, Math.min(dp(410), Math.max(1, usable.width() - margin * 2)));
        int desired = minW;
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setTextSize(spPx(16f));
        desired = Math.max(desired, measuredTextWidth(text, p) + dp(BOX_HPAD_DP * 2 + 16));
        if (blocks != null) {
            for (String block : blocks) {
                desired = Math.max(desired,
                        measuredTextWidth(block, p) + dp(BOX_HPAD_DP * 2 + 16));
            }
        }
        if (image != null && image.getWidth() > 0) {
            float density = Math.max(.1f, getResources().getDisplayMetrics().density);
            int sourceDpWidth = Math.round(image.getWidth() / density);
            desired = Math.max(desired,
                    dp(Math.min(360, Math.max(170, sourceDpWidth))) + dp(BOX_HPAD_DP * 2));
        }
        return clamp(desired, minW, maxW);
    }

    private int measuredTextWidth(String text, TextPaint p) {
        if (text == null || text.isEmpty()) return 0;
        float max = 0f;
        for (String line : text.split("\\n", -1)) max = Math.max(max, p.measureText(line));
        return Math.round(max);
    }

    private int textHeight(String text, int widthPx, float textSp, int extraDp) {
        return wrappedLines(text, widthPx, textSp) * dp(22) + dp(extraDp);
    }

    private int wrappedLines(String text, int widthPx, float textSp) {
        if (text == null || text.isBlank() || widthPx <= 0) return 1;
        try {
            TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            p.setTextSize(spPx(textSp));
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), p, widthPx)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
            return Math.max(1, layout.getLineCount());
        } catch (Throwable ignored) {
            return Math.max(1, text.split("\\n", -1).length);
        }
    }

    private Rect usableBounds() {
        try {
            var metrics = getWindowManager().getMaximumWindowMetrics();
            Rect r = new Rect(metrics.getBounds());
            var insets = metrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            r.left += insets.left;
            r.top += insets.top;
            r.right -= insets.right;
            r.bottom -= insets.bottom;
            if (!r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        return new Rect(0, 0, getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
    }

    private void positionWindow(Rect usable, int popupW, int popupH, Rect anchor) {
        Window w = getWindow();
        w.setLayout(popupW, popupH);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = popupW;
        lp.height = popupH;
        lp.gravity = Gravity.TOP | Gravity.START;
        Rect normalized = normalizeAnchor(anchor, usable);
        int[] xy = choosePosition(usable, normalized, popupW, popupH);
        lp.x = xy[0];
        lp.y = xy[1];
        w.setAttributes(lp);
        DiagnosticLog.i(this, "RESULT_TEXT_ACTIVITY", "WINDOW size=" + popupW + "x" + popupH
                + " pos=" + lp.x + "," + lp.y
                + " anchor=" + (normalized == null ? "none" : normalized.toShortString()));
    }

    private int[] choosePosition(Rect usable, Rect anchor, int w, int h) {
        int margin = dp(OUTER_MARGIN_DP);
        int gap = dp(ANCHOR_GAP_DP);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - h);
        if (anchor == null || anchor.isEmpty()) {
            return new int[]{
                    clamp(usable.centerX() - w / 2, minX, maxX),
                    clamp(usable.centerY() - h / 2, minY, maxY)
            };
        }

        int cx = anchor.centerX(), cy = anchor.centerY();
        ArrayList<Placement> choices = new ArrayList<>();
        choices.add(new Placement(cx - w / 2, anchor.bottom + gap, 0));
        choices.add(new Placement(cx - w / 2, anchor.top - gap - h, 1));
        choices.add(new Placement(anchor.right + gap, cy - h / 2, 2));
        choices.add(new Placement(anchor.left - gap - w, cy - h / 2, 3));
        for (Placement p : choices) {
            p.fit = p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY;
            p.cx = clamp(p.x, minX, maxX);
            p.cy = clamp(p.y, minY, maxY);
            Rect placed = new Rect(p.cx, p.cy, p.cx + w, p.cy + h);
            Rect overlap = new Rect(placed);
            p.overlap = overlap.intersect(anchor) ? (long) overlap.width() * overlap.height() : 0L;
            p.shift = Math.abs(p.cx - p.x) + Math.abs(p.cy - p.y);
        }
        choices.sort(Comparator.comparing((Placement p) -> !p.fit)
                .thenComparingLong(p -> p.overlap)
                .thenComparingInt(p -> p.shift)
                .thenComparingInt(p -> p.preference));
        Placement best = choices.get(0);
        return new int[]{best.cx, best.cy};
    }

    private Rect normalizeAnchor(Rect anchor, Rect usable) {
        if (anchor == null || anchor.isEmpty()) return null;
        Rect r = new Rect(anchor);
        return r.intersect(usable) ? r : null;
    }

    private void finishWithCircle(String reason) {
        if (activeBlockActionMode != null) {
            try { activeBlockActionMode.finish(); } catch (Throwable ignored) {}
            activeBlockActionMode = null;
        }
        if (!circleFinished) {
            circleFinished = true;
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished(reason);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onBackPressed() {
        if (activeBlockActionMode != null) {
            ActionMode old = activeBlockActionMode;
            activeBlockActionMode = null;
            try { old.finish(); } catch (Throwable ignored) {}
            return;
        }
        finishWithCircle("result_back");
    }

    @Override protected void onDestroy() {
        if (activeBlockActionMode != null) {
            try { activeBlockActionMode.finish(); } catch (Throwable ignored) {}
            activeBlockActionMode = null;
        }
        if (token != 0L) PENDING.remove(token);
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private float spPx(float sp) { return sp * getResources().getDisplayMetrics().scaledDensity; }
    private int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private static final class Payload {
        final String text;
        final List<String> blocks;
        final Bitmap image;
        final Rect anchor;

        Payload(String text, List<String> blocks, Bitmap image, Rect anchor) {
            this.text = text == null ? "" : text;
            this.blocks = blocks == null ? new ArrayList<>() : new ArrayList<>(blocks);
            this.image = image;
            this.anchor = anchor == null ? null : new Rect(anchor);
        }
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
}
