package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** FloatLens-owned text action menu and explicit app picker. */
public final class FloatActionMenu {
    private static final int MODE_MAIN = 0;
    private static final int MODE_SHARE = 1;
    private static final int MODE_PROCESS = 2;

    private static WindowManager activeWm;
    private static View activeView;

    public static void showText(Context c, String value, Runnable selectAll) {
        show(c, value, selectAll, MODE_MAIN);
    }

    public static void showShareTargets(Context c, String value) {
        show(c, value, null, MODE_SHARE);
    }

    public static void showProcessTargets(Context c, String value) {
        show(c, value, null, MODE_PROCESS);
    }

    private static synchronized void show(Context c, String value, Runnable selectAll, int mode) {
        if (c == null) return;
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return;
        dismiss();

        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        LinearLayout root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(app, 12), dp(app, 10), dp(app, 12), dp(app, 10));
        root.setBackgroundColor(0xF5222326);
        root.setElevation(dp(app, 14));
        root.setClickable(true);

        LinearLayout header = new LinearLayout(app);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(app);
        title.setText(mode == MODE_SHARE ? "分享到" : mode == MODE_PROCESS ? "打开 / 处理" : "FloatLens");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        Button close = button(app, "×");
        header.addView(title, new LinearLayout.LayoutParams(0, dp(app, 40), 1));
        header.addView(close, new LinearLayout.LayoutParams(dp(app, 44), dp(app, 40)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(app, 40)));

        TextView preview = new TextView(app);
        preview.setText(text);
        preview.setTextColor(0xFFDDDDDD);
        preview.setTextSize(14);
        preview.setMaxLines(2);
        preview.setPadding(dp(app, 4), 0, dp(app, 4), dp(app, 8));
        root.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        if (mode == MODE_MAIN) {
            LinearLayout row = new LinearLayout(app);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button copy = button(app, "复制");
            Button share = button(app, "分享");
            Button process = button(app, "打开/处理");
            row.addView(copy, new LinearLayout.LayoutParams(0, dp(app, 46), 1));
            row.addView(share, new LinearLayout.LayoutParams(0, dp(app, 46), 1));
            row.addView(process, new LinearLayout.LayoutParams(0, dp(app, 46), 1));
            if (selectAll != null) {
                Button all = button(app, "全选");
                row.addView(all, new LinearLayout.LayoutParams(0, dp(app, 46), 1));
                all.setOnClickListener(v -> {
                    try { selectAll.run(); } catch (Throwable ignored) {}
                    dismiss();
                });
            }
            root.addView(row, new LinearLayout.LayoutParams(-1, dp(app, 46)));
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", text));
                Toast.makeText(app, "已复制", Toast.LENGTH_SHORT).show();
                dismiss();
            });
            share.setOnClickListener(v -> show(app, text, null, MODE_SHARE));
            process.setOnClickListener(v -> show(app, text, null, MODE_PROCESS));
        } else {
            addTargets(app, root, text, mode);
        }

        close.setOnClickListener(v -> dismiss());
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        Rect usable = usableBounds(app, wm);
        int width = Math.max(dp(app, 260), Math.min(dp(app, 430), usable.width() - dp(app, 20)));
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
        lp.x = usable.left + Math.max(0, (usable.width() - width) / 2);
        lp.y = usable.top + dp(app, 54);
        try {
            wm.addView(root, lp);
            activeWm = wm;
            activeView = root;
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show mode=" + mode + " chars=" + text.length());
        } catch (Throwable t) {
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show failed=" + t);
            if (mode == MODE_SHARE) launchSystemShare(app, text);
            else if (mode == MODE_PROCESS) launchSystemProcess(app, text);
        }
    }

    private static void addTargets(Context app, LinearLayout root, String text, int mode) {
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
        resolved.removeIf(ri -> ri == null || ri.activityInfo == null
                || app.getPackageName().equals(ri.activityInfo.packageName));
        resolved.sort(Comparator.comparing(ri -> {
            try {
                CharSequence s = ri.loadLabel(pm);
                return s == null ? ri.activityInfo.name : s.toString();
            } catch (Throwable ignored) {
                return ri.activityInfo.name;
            }
        }, String.CASE_INSENSITIVE_ORDER));

        ScrollView scroll = new ScrollView(app);
        LinearLayout list = new LinearLayout(app);
        list.setOrientation(LinearLayout.VERTICAL);
        if (resolved.isEmpty()) {
            TextView none = new TextView(app);
            none.setText(mode == MODE_SHARE ? "没有找到可分享的应用" : "没有找到可处理文字的应用");
            none.setTextColor(0xFFCCCCCC);
            none.setPadding(dp(app, 8), dp(app, 10), dp(app, 8), dp(app, 10));
            list.addView(none);
        } else {
            for (ResolveInfo ri : resolved) {
                CharSequence label;
                try { label = ri.loadLabel(pm); }
                catch (Throwable ignored) { label = ri.activityInfo.name; }
                Button target = button(app, label == null ? ri.activityInfo.name : label.toString());
                target.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                target.setAllCaps(false);
                try {
                    Drawable icon = ri.loadIcon(pm);
                    if (icon != null) {
                        int s = dp(app, 26);
                        icon.setBounds(0, 0, s, s);
                        target.setCompoundDrawablePadding(dp(app, 10));
                        target.setCompoundDrawables(icon, null, null, null);
                    }
                } catch (Throwable ignored) {}
                target.setOnClickListener(v -> launchExplicit(app, base, ri));
                list.addView(target, new LinearLayout.LayoutParams(-1, dp(app, 50)));
            }
        }
        scroll.addView(list);
        int visibleRows = Math.min(Math.max(1, resolved.size()), 6);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(app, 50 * visibleRows)));

        Button system = button(app, mode == MODE_SHARE ? "系统分享菜单" : "系统处理菜单");
        root.addView(system, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        system.setOnClickListener(v -> {
            dismiss();
            if (mode == MODE_SHARE) launchSystemShare(app, text);
            else launchSystemProcess(app, text);
        });
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

    private static void launchSystemShare(Context app, String text) {
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

    private static Button button(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        return b;
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

    private FloatActionMenu() {}
}
