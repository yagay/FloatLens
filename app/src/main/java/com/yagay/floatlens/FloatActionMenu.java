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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FloatLens-owned text action menu styled like Android's floating text toolbar. */
public final class FloatActionMenu {
    private static final int MODE_MAIN = 0;
    private static final int MODE_SHARE = 1;
    private static final int MODE_PROCESS = 2;
    private static final int MODE_MORE = 3;

    private static WindowManager activeWm;
    private static View activeView;

    public static void showText(Context c, String value, Runnable selectAll) {
        show(c, value, selectAll, MODE_MAIN);
    }

    public static void showShareTargets(Context c, String value) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return;
        if (TargetMenuStore.isCustomized(app, TargetMenuStore.MODE_SHARE)) {
            show(app, text, null, MODE_SHARE);
        } else {
            dismiss();
            launchSystemShare(app, text);
        }
    }

    public static void showProcessTargets(Context c, String value) {
        show(c, value, null, MODE_PROCESS);
    }

    private static synchronized void show(Context c, String value, Runnable selectAll, int mode) {
        if (c == null) return;
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return;

        Context app = c.getApplicationContext();
        if (mode == MODE_SHARE && !TargetMenuStore.isCustomized(app, TargetMenuStore.MODE_SHARE)) {
            dismiss();
            launchSystemShare(app, text);
            return;
        }

        dismiss();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
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
        int width = mode == MODE_MAIN
                ? WindowManager.LayoutParams.WRAP_CONTENT
                : Math.max(dp(app, 270), Math.min(dp(app, 360), usable.width() - dp(app, 24)));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = usable.top + dp(app, 52);
        try {
            wm.addView(root, lp);
            activeWm = wm;
            activeView = root;
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show system-style mode=" + mode
                    + " chars=" + text.length());
        } catch (Throwable t) {
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show failed=" + t);
            if (mode == MODE_SHARE) launchSystemShare(app, text);
            else if (mode == MODE_PROCESS) launchSystemProcess(app, text);
        }
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
        share.setOnClickListener(v -> {
            if (TargetMenuStore.isCustomized(app, TargetMenuStore.MODE_SHARE)) {
                show(app, text, selectAll, MODE_SHARE);
            } else {
                dismiss();
                launchSystemShare(app, text);
            }
        });
        more.setOnClickListener(v -> show(app, text, selectAll, MODE_MORE));
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
        back.setOnClickListener(v -> show(app, text, selectAll, MODE_MAIN));

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
        process.setOnClickListener(v -> show(app, text, selectAll, MODE_PROCESS));
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
        back.setOnClickListener(v -> show(app, text, selectAll, MODE_MAIN));

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

    private static List<ResolveInfo> applyTargetCustomization(Context app, String mode,
                                                              List<ResolveInfo> resolved) {
        PackageManager pm = app.getPackageManager();
        if (!TargetMenuStore.isCustomized(app, mode)) {
            resolved.sort(Comparator.comparing(ri -> {
                try {
                    CharSequence s = ri.loadLabel(pm);
                    return s == null ? ri.activityInfo.name : s.toString();
                } catch (Throwable ignored) {
                    return ri.activityInfo.name;
                }
            }, String.CASE_INSENSITIVE_ORDER));
            return resolved;
        }

        Map<String, ResolveInfo> byComponent = new HashMap<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            byComponent.put(ri.activityInfo.packageName + "|" + ri.activityInfo.name, ri);
        }
        ArrayList<ResolveInfo> ordered = new ArrayList<>();
        for (TargetMenuStore.Item item : TargetMenuStore.load(app, mode)) {
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
        WindowManager wm = activeWm;
        activeView = null;
        activeWm = null;
        if (v != null && wm != null) {
            try { wm.removeView(v); } catch (Throwable ignored) {}
        }
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
