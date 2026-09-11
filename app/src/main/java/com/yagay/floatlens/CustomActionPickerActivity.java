package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
import java.util.Set;

/** Picker that discovers custom text-menu actions either by app or by intent type. */
public final class CustomActionPickerActivity extends AppCompatActivity {
    private final PackageManager pm() { return getPackageManager(); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showHome();
    }

    private void showHome() {
        LinearLayout root = page("自定义文字菜单");
        TextView help = text("不需要填写包名、Activity、Action 或 MIME。可以先按 App 选择，也可以先按 Intent 类型选择。FloatLens 会自动保存实际入口。", 14);
        help.setPadding(0, 0, 0, dp(14));
        root.addView(help);

        Button byApp = button("按 App 选择");
        byApp.setOnClickListener(v -> showApps());
        root.addView(byApp);

        Button byType = button("按 Intent 类型选择");
        byType.setOnClickListener(v -> showIntentTypes());
        root.addView(byType);

        List<CustomMenuActionStore.Item> items = CustomMenuActionStore.load(this);
        TextView current = text("已加入菜单（" + items.size() + "）", 18);
        current.setPadding(0, dp(22), 0, dp(8));
        root.addView(current);
        if (items.isEmpty()) {
            TextView empty = text("还没有自定义菜单项", 14);
            empty.setAlpha(.7f);
            root.addView(empty);
        } else {
            for (CustomMenuActionStore.Item item : items) {
                Drawable icon = appIcon(item.packageName);
                String sub = CustomMenuActionStore.typeLabel(item.type);
                View row = row(item.label, sub, icon, () -> {
                    CustomMenuActionStore.remove(this, item.id);
                    Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                    showHome();
                }, "移除");
                root.addView(row);
            }
        }
        setPage(root);
    }

    private void showApps() {
        LinearLayout root = page("按 App 选择");
        addBack(root, this::showHome);
        TextView note = text("先点 App，再从它自动识别出的 Intent 入口或可导出 Activity 中选择。", 14);
        note.setPadding(0, 0, 0, dp(10));
        root.addView(note);

        List<ApplicationInfo> apps;
        try { apps = pm().getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)); }
        catch (Throwable ignored) { apps = pm().getInstalledApplications(0); }
        apps.removeIf(a -> a == null || !a.enabled || getPackageName().equals(a.packageName));
        apps.sort(Comparator.comparing(a -> appLabel(a), String.CASE_INSENSITIVE_ORDER));
        for (ApplicationInfo app : apps) {
            String label = appLabel(app);
            Drawable icon = null;
            try { icon = app.loadIcon(pm()); } catch (Throwable ignored) {}
            final String pkg = app.packageName;
            root.addView(row(label, pkg, icon, () -> showAppActions(pkg, label), null));
        }
        setPage(root);
    }

    private void showAppActions(String pkg, String appLabel) {
        LinearLayout root = page(appLabel);
        addBack(root, this::showApps);
        TextView note = text("以下入口由 FloatLens 自动读取。标准 Intent 入口优先显示，后面再列出其他可直接启动的 exported Activity。", 14);
        note.setPadding(0, 0, 0, dp(10));
        root.addView(note);

        ArrayList<Discovered> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IntentTypeCatalog.Spec spec : IntentTypeCatalog.all()) {
            Intent probe = spec.probeIntent().setPackage(pkg);
            List<ResolveInfo> matches = query(probe);
            for (ResolveInfo ri : matches) {
                if (ri.activityInfo == null) continue;
                String key = spec.type + "|" + ri.activityInfo.packageName + "|" + ri.activityInfo.name;
                if (!seen.add(key)) continue;
                String activityLabel = resolveLabel(ri);
                all.add(new Discovered(appLabel + " · " + spec.title,
                        activityLabel, appIcon(pkg), ri.activityInfo.packageName,
                        ri.activityInfo.name, spec.type));
            }
        }

        try {
            PackageInfo pi = pm().getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES));
            if (pi.activities != null) {
                ArrayList<ActivityInfo> acts = new ArrayList<>(List.of(pi.activities));
                acts.sort(Comparator.comparing(a -> activityLabel(a), String.CASE_INSENSITIVE_ORDER));
                for (ActivityInfo ai : acts) {
                    if (!canDirectLaunch(ai)) continue;
                    String key = CustomMenuActionStore.TYPE_ACTIVITY + "|" + ai.packageName + "|" + ai.name;
                    if (!seen.add(key)) continue;
                    all.add(new Discovered(activityLabel(ai), "直接打开入口 · " + shortClass(ai.name),
                            appIcon(pkg), ai.packageName, ai.name, CustomMenuActionStore.TYPE_ACTIVITY));
                }
            }
        } catch (Throwable t) {
            try {
                PackageInfo pi = pm().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                if (pi.activities != null) {
                    for (ActivityInfo ai : pi.activities) {
                        if (!canDirectLaunch(ai)) continue;
                        String key = CustomMenuActionStore.TYPE_ACTIVITY + "|" + ai.packageName + "|" + ai.name;
                        if (!seen.add(key)) continue;
                        all.add(new Discovered(activityLabel(ai), "直接打开入口 · " + shortClass(ai.name),
                                appIcon(pkg), ai.packageName, ai.name, CustomMenuActionStore.TYPE_ACTIVITY));
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (all.isEmpty()) {
            TextView empty = text("没有发现可从 FloatLens 调用的入口", 15);
            empty.setPadding(0, dp(16), 0, 0);
            root.addView(empty);
        } else {
            for (Discovered d : all) root.addView(discoveredRow(d));
        }
        setPage(root);
    }

    private void showIntentTypes() {
        LinearLayout root = page("按 Intent 类型选择");
        addBack(root, this::showHome);
        for (IntentTypeCatalog.Spec spec : IntentTypeCatalog.all()) {
            root.addView(row(spec.title, spec.description, null,
                    () -> showHandlersForType(spec), null));
        }
        setPage(root);
    }

    private void showHandlersForType(IntentTypeCatalog.Spec spec) {
        LinearLayout root = page(spec.title);
        addBack(root, this::showIntentTypes);
        TextView note = text(spec.description + " · 下面只显示系统确认能处理这个 Intent 的应用入口。", 14);
        note.setPadding(0, 0, 0, dp(10));
        root.addView(note);

        List<ResolveInfo> handlers = query(spec.probeIntent());
        handlers.removeIf(ri -> ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName));
        handlers.sort(Comparator.comparing(this::resolveAppThenActivityLabel, String.CASE_INSENSITIVE_ORDER));
        if (handlers.isEmpty()) {
            root.addView(text("没有找到可处理此 Intent 的应用", 15));
        } else {
            for (ResolveInfo ri : handlers) {
                ActivityInfo ai = ri.activityInfo;
                String app = appLabel(ai.applicationInfo);
                String activity = resolveLabel(ri);
                Discovered d = new Discovered(app + " · " + spec.title,
                        activity, appIcon(ai.packageName), ai.packageName, ai.name, spec.type);
                root.addView(discoveredRow(d));
            }
        }
        setPage(root);
    }

    private View discoveredRow(Discovered d) {
        return row(d.label, d.subtitle, d.icon, () -> {
            CustomMenuActionStore.Item item = new CustomMenuActionStore.Item(null,
                    d.label, d.pkg, d.cls, d.type);
            boolean added = CustomMenuActionStore.add(this, item);
            Toast.makeText(this, added ? "已加入 FloatLens 菜单" : "这个入口已经加入过了",
                    Toast.LENGTH_SHORT).show();
            if (added) showHome();
        }, "加入");
    }

    private boolean canDirectLaunch(ActivityInfo ai) {
        if (ai == null || !ai.exported || !ai.enabled) return false;
        if (ai.permission == null || ai.permission.isBlank()) return true;
        return checkSelfPermission(ai.permission) == PackageManager.PERMISSION_GRANTED;
    }

    private List<ResolveInfo> query(Intent intent) {
        try {
            List<ResolveInfo> list = pm().queryIntentActivities(intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Throwable ignored) {
            try {
                List<ResolveInfo> list = pm().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                return list == null ? new ArrayList<>() : new ArrayList<>(list);
            } catch (Throwable t) {
                return new ArrayList<>();
            }
        }
    }

    private String resolveAppThenActivityLabel(ResolveInfo ri) {
        if (ri == null || ri.activityInfo == null) return "";
        return appLabel(ri.activityInfo.applicationInfo) + " " + resolveLabel(ri);
    }

    private String resolveLabel(ResolveInfo ri) {
        try {
            CharSequence c = ri.loadLabel(pm());
            if (c != null && !c.toString().isBlank()) return c.toString();
        } catch (Throwable ignored) {}
        return ri.activityInfo == null ? "Activity" : activityLabel(ri.activityInfo);
    }

    private String activityLabel(ActivityInfo ai) {
        try {
            CharSequence c = ai.loadLabel(pm());
            if (c != null && !c.toString().isBlank()) return c.toString();
        } catch (Throwable ignored) {}
        return shortClass(ai == null ? "" : ai.name);
    }

    private String appLabel(ApplicationInfo ai) {
        if (ai == null) return "应用";
        try {
            CharSequence c = ai.loadLabel(pm());
            if (c != null && !c.toString().isBlank()) return c.toString();
        } catch (Throwable ignored) {}
        return ai.packageName;
    }

    private Drawable appIcon(String pkg) {
        try { return pm().getApplicationIcon(pkg); } catch (Throwable ignored) { return null; }
    }

    private String shortClass(String name) {
        if (name == null) return "Activity";
        int p = name.lastIndexOf('.');
        return p >= 0 ? name.substring(p + 1) : name;
    }

    private LinearLayout page(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        TextView t = text(title, 22);
        t.setPadding(0, 0, 0, dp(12));
        root.addView(t);
        return root;
    }

    private void setPage(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        setContentView(sv);
    }

    private void addBack(LinearLayout root, Runnable action) {
        Button back = button("‹ 返回");
        back.setOnClickListener(v -> action.run());
        root.addView(back);
    }

    private View row(String title, String subtitle, Drawable icon, Runnable action, String sideText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(7), dp(4), dp(7));
        row.setClickable(true);
        row.setFocusable(true);

        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            int s = dp(40);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
            ip.setMarginEnd(dp(12));
            row.addView(iv, ip);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(title, 16);
        a.setSingleLine(true);
        texts.addView(a);
        if (subtitle != null && !subtitle.isBlank()) {
            TextView b = text(subtitle, 12);
            b.setAlpha(.65f);
            b.setSingleLine(true);
            texts.addView(b);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, dp(54), 1));

        if (sideText != null) {
            TextView side = text(sideText, 13);
            side.setGravity(Gravity.CENTER);
            side.setPadding(dp(10), 0, dp(10), 0);
            side.setOnClickListener(v -> action.run());
            row.addView(side, new LinearLayout.LayoutParams(dp(58), dp(44)));
        }
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private TextView text(String value, int sp) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        return tv;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        showHome();
    }

    private static final class Discovered {
        final String label, subtitle, pkg, cls, type;
        final Drawable icon;
        Discovered(String label, String subtitle, Drawable icon, String pkg, String cls, String type) {
            this.label = label;
            this.subtitle = subtitle;
            this.icon = icon;
            this.pkg = pkg;
            this.cls = cls;
            this.type = type;
        }
    }
}
