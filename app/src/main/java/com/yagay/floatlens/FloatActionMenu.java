package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FloatLens-owned text action menu styled like Android's floating text toolbar. */
public final class FloatActionMenu {
    private static final int MODE_MAIN = 0;
    private static final int MODE_SHARE = 1;
    private static final int MODE_PROCESS = 2;
    private static final int MODE_MORE = 3;
    private static final int NO_POSITION = Integer.MIN_VALUE;

    private static FvOverlayWindowHost activeHost;
    private static View activeView;
    private static int activeCenterX = NO_POSITION;
    private static int activeTopY = NO_POSITION;
    private static int lockedCenterX = NO_POSITION;
    private static int lockedTopY = NO_POSITION;

    public static void showText(Context c, String value, Runnable selectAll) {
        resetLockedRow();
        FloatMenuAnchor.clear();
        show(c, value, selectAll, MODE_MAIN);
    }

    public static void showTextAt(Context c, String value, Runnable selectAll, Rect anchor) {
        resetLockedRow();
        FloatMenuAnchor.set(anchor);
        show(c, value, selectAll, MODE_MAIN);
    }

    public static void showShareTargets(Context c, String value) {
        resetLockedRow();
        FloatMenuAnchor.clear();
        show(c, value, null, MODE_SHARE);
    }

    public static void showProcessTargets(Context c, String value) {
        resetLockedRow();
        FloatMenuAnchor.clear();
        show(c, value, null, MODE_PROCESS);
    }

    /** Keep submenus/back navigation attached to the row where the main toolbar first appeared. */
    private static void showOnCurrentRow(Context c, String value, Runnable selectAll, int mode) {
        if (!hasLockedRow() && activeCenterX != NO_POSITION && activeTopY != NO_POSITION) {
            lockedCenterX = activeCenterX;
            lockedTopY = activeTopY;
        }
        show(c, value, selectAll, mode);
    }

    private static synchronized void show(Context c, String value, Runnable selectAll, int mode) {
        if (c == null) return;
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return;
        dismiss();

        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        FvOverlayWindowHost host = new FvOverlayWindowHost(app);
        Palette palette = Palette.from(app);

        LinearLayout root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(palette.surface, dp(app, mode == MODE_MAIN ? 24 : 20)));
        root.setElevation(dp(app, 10));
        root.setClipToOutline(true);
        root.setClickable(true);

        if (mode == MODE_MAIN) {
            buildMainToolbar(app, root, text, selectAll, palette);
        } else if (mode == MODE_MORE) {
            buildMoreMenu(app, root, text, selectAll, palette);
        } else {
            buildTargetMenu(app, root, text, selectAll, mode, palette);
        }

        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        Rect usable = usableBounds(app, wm);
        int maxPopupWidth = Math.max(dp(app, 120), usable.width() - dp(app, 16));
        int width = mode == MODE_MAIN
                ? WindowManager.LayoutParams.WRAP_CONTENT
                : contentAdaptiveWidth(app, root, mode, maxPopupWidth);

        int maxMeasureW = Math.max(dp(app, 46), usable.width() - dp(app, 16));
        int maxMeasureH = Math.max(dp(app, 46), usable.height() - dp(app, 16));
        int widthSpec = width == WindowManager.LayoutParams.WRAP_CONTENT
                ? View.MeasureSpec.makeMeasureSpec(maxMeasureW, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(maxMeasureH, View.MeasureSpec.AT_MOST);
        root.measure(widthSpec, heightSpec);
        int menuWidth = width == WindowManager.LayoutParams.WRAP_CONTENT
                ? Math.max(dp(app, 46), root.getMeasuredWidth()) : width;
        int menuHeight = Math.max(dp(app, 46), root.getMeasuredHeight());

        int[] pos = hasLockedRow()
                ? lockedRowPosition(app, usable, menuWidth)
                : menuPosition(app, usable, FloatMenuAnchor.current(), menuWidth, menuHeight);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = pos[0];
        lp.y = pos[1];
        if (host.add(root, lp, "float_action_menu")) {
            activeHost = host;
            activeView = root;
            activeCenterX = lp.x + menuWidth / 2;
            activeTopY = lp.y;
            Rect anchor = FloatMenuAnchor.current();
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show system-style mode=" + mode
                    + " chars=" + text.length()
                    + " anchor=" + (anchor == null ? "none" : anchor.toShortString())
                    + " lockedRow=" + (hasLockedRow() ? lockedTopY : -1)
                    + " width=" + menuWidth
                    + " pos=" + lp.x + "," + lp.y
                    + " accessibilityHost=" + host.isAccessibilityHosted()
                    + " type=" + lp.type);
        } else {
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show failed all hosts mode=" + mode);
            if (mode == MODE_SHARE) launchSystemShare(app, text);
            else if (mode == MODE_PROCESS) launchSystemProcess(app, text);
        }
    }

    /**
     * Match Android's compact popup feel: short menus stay narrow, longer labels grow only as needed.
     * The old implementation forced every submenu to at least 270dp, which made even tiny menus wide.
     */
    private static int contentAdaptiveWidth(Context app, View root, int mode, int screenMax) {
        int min = dp(app, mode == MODE_MORE ? 156 : 188);
        int cap = Math.min(screenMax, dp(app, mode == MODE_MORE ? 260 : 300));
        cap = Math.max(min, cap);
        int desired = widestTextRow(root) + root.getPaddingLeft() + root.getPaddingRight();
        return clamp(Math.max(min, desired), min, cap);
    }

    private static int widestTextRow(View view) {
        int best = 0;
        if (view instanceof TextView tv) {
            CharSequence raw = tv.getText();
            String text = raw == null ? "" : raw.toString();
            int width = (int) Math.ceil(tv.getPaint().measureText(text))
                    + tv.getPaddingLeft() + tv.getPaddingRight();
            Drawable[] drawables = tv.getCompoundDrawables();
            Drawable left = drawables != null && drawables.length > 0 ? drawables[0] : null;
            Drawable right = drawables != null && drawables.length > 2 ? drawables[2] : null;
            if (left != null) {
                int dw = left.getBounds().width();
                if (dw <= 0) dw = left.getIntrinsicWidth();
                width += Math.max(0, dw) + tv.getCompoundDrawablePadding();
            }
            if (right != null) {
                int dw = right.getBounds().width();
                if (dw <= 0) dw = right.getIntrinsicWidth();
                width += Math.max(0, dw) + tv.getCompoundDrawablePadding();
            }
            best = width;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                best = Math.max(best, widestTextRow(group.getChildAt(i)));
            }
        }
        return best;
    }

    private static int[] lockedRowPosition(Context app, Rect usable, int menuWidth) {
        int margin = dp(app, 8);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - menuWidth);
        int minY = usable.top + margin;
        // Preserve the original toolbar row. Only clamp enough to keep that row itself visible.
        int maxRowTop = Math.max(minY, usable.bottom - margin - dp(app, 46));
        int x = clamp(lockedCenterX - menuWidth / 2, minX, maxX);
        int y = clamp(lockedTopY, minY, maxRowTop);
        return new int[]{x, y};
    }

    private static int[] menuPosition(Context app, Rect usable, Rect anchor,
                                      int menuWidth, int menuHeight) {
        int margin = dp(app, 8);
        int gap = dp(app, 8);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - menuWidth);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - menuHeight);

        if (anchor == null || anchor.isEmpty()) {
            int x = clamp(usable.centerX() - menuWidth / 2, minX, maxX);
            int y = clamp(usable.top + dp(app, 52), minY, maxY);
            return new int[]{x, y};
        }

        int x = clamp(anchor.centerX() - menuWidth / 2, minX, maxX);
        int above = anchor.top - gap - menuHeight;
        int below = anchor.bottom + gap;
        int y;
        if (above >= minY) {
            y = above;
        } else if (below <= maxY) {
            y = below;
        } else {
            int roomAbove = Math.max(0, anchor.top - minY);
            int roomBelow = Math.max(0, usable.bottom - margin - anchor.bottom);
            y = roomBelow >= roomAbove
                    ? clamp(below, minY, maxY)
                    : clamp(above, minY, maxY);
        }
        return new int[]{x, y};
    }

    private static void buildMainToolbar(Context app, LinearLayout root, String text,
                                         Runnable selectAll, Palette palette) {
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(app, 2), dp(app, 2), dp(app, 2), dp(app, 2));

        TextView copy = action(app, "复制", palette, 58);
        row.addView(copy, new LinearLayout.LayoutParams(dp(app, 58), dp(app, 46)));

        if (selectAll != null) {
            TextView all = action(app, "全选", palette, 58);
            row.addView(all, new LinearLayout.LayoutParams(dp(app, 58), dp(app, 46)));
            all.setOnClickListener(v -> {
                try { selectAll.run(); } catch (Throwable ignored) {}
                dismiss();
            });
        }

        TextView share = action(app, "分享", palette, 58);
        row.addView(share, new LinearLayout.LayoutParams(dp(app, 58), dp(app, 46)));

        List<CustomMenuActionStore.Item> customs = CustomMenuActionStore.load(app);
        int customLimit = mainCustomCount(selectAll, customs.size());
        for (int i = 0; i < customLimit; i++) {
            CustomMenuActionStore.Item item = customs.get(i);
            TextView custom = action(app, item.label, palette, 72);
            custom.setMaxWidth(dp(app, 88));
            custom.setEllipsize(TextUtils.TruncateAt.END);
            custom.setSingleLine(true);
            row.addView(custom, new LinearLayout.LayoutParams(dp(app, 78), dp(app, 46)));
            custom.setOnClickListener(v -> launchCustom(app, item, text));
        }

        TextView more = action(app, "⋮", palette, 46);
        more.setTextSize(24);
        row.addView(more, new LinearLayout.LayoutParams(dp(app, 46), dp(app, 46)));
        root.addView(row);

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", text));
            Toast.makeText(app, "已复制", Toast.LENGTH_SHORT).show();
            dismiss();
        });
        share.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_SHARE));
        more.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_MORE));
    }

    private static int mainCustomCount(Runnable selectAll, int size) {
        int max = selectAll != null ? 1 : 2;
        return Math.min(max, Math.max(0, size));
    }

    private static void buildMoreMenu(Context app, LinearLayout root, String text,
                                      Runnable selectAll, Palette palette) {
        root.setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4));
        TextView back = menuRow(app, "‹   返回", null, palette);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        back.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_MAIN));

        List<CustomMenuActionStore.Item> customs = CustomMenuActionStore.load(app);
        int skip = mainCustomCount(selectAll, customs.size());
        for (int i = skip; i < customs.size(); i++) {
            CustomMenuActionStore.Item item = customs.get(i);
            Drawable icon = null;
            try { icon = app.getPackageManager().getApplicationIcon(item.packageName); }
            catch (Throwable ignored) {}
            TextView custom = menuRow(app, item.label, icon, palette);
            root.addView(custom, new LinearLayout.LayoutParams(-1, dp(app, 48)));
            custom.setOnClickListener(v -> launchCustom(app, item, text));
        }

        TextView process = menuRow(app, "打开 / 处理", null, palette);
        root.addView(process, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        process.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_PROCESS));
    }

    private static void launchCustom(Context app, CustomMenuActionStore.Item item, String text) {
        dismiss();
        CustomMenuActionStore.launch(app, item, text);
    }

    private static void buildTargetMenu(Context app, LinearLayout root, String text,
                                        Runnable selectAll, int mode, Palette palette) {
        root.setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4));

        TextView back = menuRow(app,
                mode == MODE_SHARE ? "‹   分享到" : "‹   打开 / 处理",
                null, palette);
        back.setTypeface(back.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        back.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_MAIN));

        PackageManager pm = app.getPackageManager();
        Intent base = mode == MODE_SHARE
                ? new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                : new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);

        List<ResolveInfo> resolved;
        try { resolved = pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY); }
        catch (Throwable t) { resolved = new ArrayList<>(); }
        if (resolved == null) resolved = new ArrayList<>();
        resolved = new ArrayList<>(resolved);
        resolved.removeIf(ri -> ri == null || ri.activityInfo == null
                || app.getPackageName().equals(ri.activityInfo.packageName));

        String targetMode = mode == MODE_SHARE ? TargetMenuStore.MODE_SHARE : TargetMenuStore.MODE_PROCESS;
        resolved = applyTargetCustomization(app, targetMode, resolved);

        ScrollView scroll = new ScrollView(app);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(resolved.size() > 6);
        LinearLayout list = new LinearLayout(app);
        list.setOrientation(LinearLayout.VERTICAL);
        if (resolved.isEmpty()) {
            TextView none = new TextView(app);
            none.setText(mode == MODE_SHARE ? "当前没有已启用的分享应用" : "当前没有已启用的处理应用");
            none.setTextColor(palette.secondaryText);
            none.setTextSize(14);
            none.setGravity(Gravity.CENTER_VERTICAL);
            none.setPadding(dp(app, 16), 0, dp(app, 16), 0);
            list.addView(none, new LinearLayout.LayoutParams(-1, dp(app, 48)));
        } else {
            for (ResolveInfo ri : resolved) {
                CharSequence label;
                try { label = ri.loadLabel(pm); }
                catch (Throwable ignored) { label = ri.activityInfo.name; }
                Drawable icon = null;
                try { icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                TextView target = menuRow(app,
                        label == null ? ri.activityInfo.name : label.toString(), icon, palette);
                target.setOnClickListener(v -> launchExplicit(app, base, ri));
                list.addView(target, new LinearLayout.LayoutParams(-1, dp(app, 50)));
            }
        }
        scroll.addView(list);
        int visibleRows = Math.min(Math.max(1, resolved.size()), 6);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(app, 50 * visibleRows)));

        TextView moreApps = menuRow(app,
                mode == MODE_SHARE ? "系统分享菜单…" : "更多处理应用…",
                null, palette);
        root.addView(moreApps, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        moreApps.setOnClickListener(v -> {
            dismiss();
            if (mode == MODE_SHARE) launchSystemShare(app, text);
            else launchSystemProcess(app, text);
        });
    }

    /** Keep Android as the source of targets; FloatLens only layers its saved ordering/hide rules. */
    private static List<ResolveInfo> applyTargetCustomization(Context app, String mode,
                                                              List<ResolveInfo> resolved) {
        if (!TargetMenuStore.isCustomized(app, mode)) return resolved;

        PackageManager pm = app.getPackageManager();
        Map<String, ResolveInfo> byComponent = new HashMap<>();
        ArrayList<TargetMenuStore.Item> systemItems = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            String key = ri.activityInfo.packageName + "|" + ri.activityInfo.name;
            byComponent.put(key, ri);
            CharSequence label;
            try { label = ri.loadLabel(pm); }
            catch (Throwable ignored) { label = ri.activityInfo.name; }
            systemItems.add(new TargetMenuStore.Item(
                    label == null ? ri.activityInfo.name : label.toString(),
                    ri.activityInfo.packageName,
                    ri.activityInfo.name));
        }

        ArrayList<ResolveInfo> ordered = new ArrayList<>();
        for (TargetMenuStore.Item item : TargetMenuStore.mergeWithSystem(app, mode, systemItems)) {
            ResolveInfo ri = byComponent.get(item.key());
            if (ri != null) ordered.add(ri);
        }
        return ordered;
    }

    private static TextView action(Context c, String text, Palette palette, int minWidthDp) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(palette.primaryText);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(dp(c, minWidthDp));
        tv.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        tv.setBackground(ripple(palette.ripple));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private static TextView menuRow(Context c, String text, Drawable icon, Palette palette) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(palette.primaryText);
        tv.setTextSize(14);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        tv.setBackground(ripple(palette.ripple));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        if (icon != null) {
            int s = dp(c, 24);
            icon.setBounds(0, 0, s, s);
            tv.setCompoundDrawablePadding(dp(c, 12));
            tv.setCompoundDrawables(icon, null, null, null);
        }
        return tv;
    }

    private static Drawable rounded(int color, float radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private static Drawable ripple(int color) {
        ColorStateList ripple = ColorStateList.valueOf(color);
        GradientDrawable content = new GradientDrawable();
        content.setColor(Color.TRANSPARENT);
        content.setCornerRadius(999f);
        return new RippleDrawable(ripple, content, null);
    }

    private static void launchExplicit(Context app, Intent base, ResolveInfo ri) {
        try {
            Intent target = new Intent(base)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            dismiss();
            app.startActivity(target);
        } catch (Throwable t) {
            Toast.makeText(app, "无法打开该应用", Toast.LENGTH_SHORT).show();
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "explicit launch failed=" + t);
        }
    }

    static void launchSystemShare(Context app, String text) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text);
            app.startActivity(Intent.createChooser(share, "分享文字")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(app, "无法打开分享菜单", Toast.LENGTH_SHORT).show();
        }
    }

    private static void launchSystemProcess(Context app, String text) {
        try {
            Intent process = new Intent(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
            app.startActivity(Intent.createChooser(process, "处理文字")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(app, "没有可用的文本处理应用", Toast.LENGTH_SHORT).show();
        }
    }

    public static synchronized void dismiss() {
        View v = activeView;
        FvOverlayWindowHost host = activeHost;
        activeView = null;
        activeHost = null;
        activeCenterX = NO_POSITION;
        activeTopY = NO_POSITION;
        if (v != null && host != null) {
            host.remove(v, "float_action_menu");
        }
    }

    private static boolean hasLockedRow() {
        return lockedCenterX != NO_POSITION && lockedTopY != NO_POSITION;
    }

    private static void resetLockedRow() {
        lockedCenterX = NO_POSITION;
        lockedTopY = NO_POSITION;
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
        return new Rect(0, 0, c.getResources().getDisplayMetrics().widthPixels,
                c.getResources().getDisplayMetrics().heightPixels);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static final class Palette {
        final int surface;
        final int primaryText;
        final int secondaryText;
        final int ripple;

        Palette(int surface, int primaryText, int secondaryText, int ripple) {
            this.surface = surface;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.ripple = ripple;
        }

        static Palette from(Context c) {
            int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean dark = night == Configuration.UI_MODE_NIGHT_YES;
            if (dark) return new Palette(0xFF2B2B2B, 0xFFF5F5F5, 0xFFB8B8B8, 0x33FFFFFF);
            return new Palette(0xFFF8F8F8, 0xFF202124, 0xFF5F6368, 0x22000000);
        }
    }

    private FloatActionMenu() {}
}
