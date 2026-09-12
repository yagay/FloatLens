package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.DragEvent;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unified menu manager for custom text actions plus share/process target ordering.
 *
 * The old CustomActionPickerActivity and TargetMenuPickerActivity independently implemented the
 * same page shell, rows, icons, drag handles and up/down/remove controls. This Activity keeps one
 * reusable UI implementation while each mode supplies only its discovery/store behaviour.
 */
public final class MenuPickerActivity extends AppCompatActivity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_MODE = "mode";
    public static final String SECTION_CUSTOM = "custom";
    public static final String SECTION_TARGET = "target";

    private String section;
    private String targetMode;

    public static Intent customIntent(Context c) {
        return new Intent(c, MenuPickerActivity.class)
                .putExtra(EXTRA_SECTION, SECTION_CUSTOM);
    }

    public static Intent targetIntent(Context c, String mode) {
        return new Intent(c, MenuPickerActivity.class)
                .putExtra(EXTRA_SECTION, SECTION_TARGET)
                .putExtra(EXTRA_MODE, TargetMenuStore.MODE_PROCESS.equals(mode)
                        ? TargetMenuStore.MODE_PROCESS : TargetMenuStore.MODE_SHARE);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        section = SECTION_TARGET.equals(getIntent().getStringExtra(EXTRA_SECTION))
                ? SECTION_TARGET : SECTION_CUSTOM;
        targetMode = TargetMenuStore.MODE_PROCESS.equals(getIntent().getStringExtra(EXTRA_MODE))
                ? TargetMenuStore.MODE_PROCESS : TargetMenuStore.MODE_SHARE;
        if (isTargetSection()) showTargetManager();
        else showCustomHome();
    }

    // -----------------------------------------------------------------------------------------
    // Custom text actions
    // -----------------------------------------------------------------------------------------

    private void showCustomHome() {
        LinearLayout root = page("自定义文字菜单");
        TextView help = text("不需要填写包名、Activity、Action 或 MIME。可以先按 App 选择，也可以先按 Intent 类型选择。FloatLens 会自动保存实际入口。", 14);
        help.setPadding(0, 0, 0, dp(14));
        root.addView(help);

        Button byApp = button("按 App 选择");
        byApp.setOnClickListener(v -> showCustomApps());
        root.addView(byApp);

        Button byType = button("按 Intent 类型选择");
        byType.setOnClickListener(v -> showCustomIntentTypes());
        root.addView(byType);

        List<CustomMenuActionStore.Item> items = CustomMenuActionStore.load(this);
        TextView current = text("已加入菜单（" + items.size() + "）", 18);
        current.setPadding(0, dp(22), 0, dp(4));
        root.addView(current);

        TextView hint = text("长按 ≡ 拖动排序；也可以用 ↑ / ↓ 精确移动。最上面的项目会优先显示在浮动菜单主栏。", 13);
        hint.setAlpha(.72f);
        hint.setPadding(0, 0, 0, dp(8));
        root.addView(hint);

        if (items.isEmpty()) {
            TextView empty = text("还没有自定义菜单项", 14);
            empty.setAlpha(.7f);
            root.addView(empty);
        } else {
            LinearLayout list = verticalList();
            list.setOnDragListener((v, event) -> onCustomSortDrag(list, event));
            for (int i = 0; i < items.size(); i++) {
                CustomMenuActionStore.Item item = items.get(i);
                final int index = i;
                list.addView(sortableRow(
                        item.id,
                        item.label,
                        CustomMenuActionStore.typeLabel(item.type),
                        appIcon(item.packageName),
                        index,
                        items.size(),
                        null,
                        () -> {
                            if (CustomMenuActionStore.move(this, item.id, -1)) showCustomHome();
                        },
                        () -> {
                            if (CustomMenuActionStore.move(this, item.id, 1)) showCustomHome();
                        },
                        () -> {
                            CustomMenuActionStore.remove(this, item.id);
                            Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                            showCustomHome();
                        }));
            }
            root.addView(list);
        }
        setPage(root);
    }

    private boolean onCustomSortDrag(LinearLayout list, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof String;
            case DragEvent.ACTION_DRAG_LOCATION:
                return true;
            case DragEvent.ACTION_DROP: {
                Object state = event.getLocalState();
                if (!(state instanceof String id)) return false;
                CustomMenuActionStore.moveTo(this, id, dropIndexForY(list, event.getY()));
                showCustomHome();
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                return true;
            default:
                return true;
        }
    }

    private void showCustomApps() {
        LinearLayout root = page("按 App 选择");
        addBack(root, this::showCustomHome);
        TextView note = text("先点 App，再从它自动识别出的 Intent 入口或可导出 Activity 中选择。", 14);
        note.setPadding(0, 0, 0, dp(10));
        root.addView(note);

        List<ApplicationInfo> apps;
        try { apps = new ArrayList<>(pm().getInstalledApplications(0)); }
        catch (Throwable t) { apps = new ArrayList<>(); }
        apps.removeIf(a -> a == null || !a.enabled || getPackageName().equals(a.packageName));
        apps.sort(Comparator.comparing(this::appLabel, String.CASE_INSENSITIVE_ORDER));
        for (ApplicationInfo app : apps) {
            String label = appLabel(app);
            Drawable icon = null;
            try { icon = app.loadIcon(pm()); } catch (Throwable ignored) {}
            final String pkg = app.packageName;
            root.addView(actionRow(label, pkg, icon, () -> showCustomAppActions(pkg, label), null));
        }
        setPage(root);
    }

    private void showCustomAppActions(String pkg, String appLabel) {
        LinearLayout root = page(appLabel);
        addBack(root, this::showCustomApps);
        TextView note = text("以下入口由 FloatLens 自动读取。标准 Intent 入口优先显示，后面再列出其他可直接启动的 exported Activity。", 14);
        note.setPadding(0, 0, 0, dp(10));
        root.addView(note);

        ArrayList<Discovered> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IntentTypeCatalog.Spec spec : IntentTypeCatalog.all()) {
            Intent probe = spec.probeIntent().setPackage(pkg);
            for (ResolveInfo ri : query(probe)) {
                if (ri.activityInfo == null) continue;
                String key = spec.type + "|" + ri.activityInfo.packageName + "|" + ri.activityInfo.name;
                if (!seen.add(key)) continue;
                all.add(new Discovered(appLabel + " · " + spec.title,
                        resolveLabel(ri), appIcon(pkg), ri.activityInfo.packageName,
                        ri.activityInfo.name, spec.type));
            }
        }

        try {
            PackageInfo pi = pm().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null) {
                ArrayList<ActivityInfo> acts = new ArrayList<>(Arrays.asList(pi.activities));
                acts.sort(Comparator.comparing(this::activityLabel, String.CASE_INSENSITIVE_ORDER));
                for (ActivityInfo ai : acts) {
                    if (!canDirectLaunch(ai)) continue;
                    String key = CustomMenuActionStore.TYPE_ACTIVITY + "|" + ai.packageName + "|" + ai.name;
                    if (!seen.add(key)) continue;
                    all.add(new Discovered(activityLabel(ai),
                            "直接打开入口 · " + shortClass(ai.name),
                            appIcon(pkg), ai.packageName, ai.name,
                            CustomMenuActionStore.TYPE_ACTIVITY));
                }
            }
        } catch (Throwable ignored) {}

        if (all.isEmpty()) {
            TextView empty = text("没有发现可从 FloatLens 调用的入口", 15);
            empty.setPadding(0, dp(16), 0, 0);
            root.addView(empty);
        } else {
            for (Discovered d : all) root.addView(customDiscoveredRow(d));
        }
        setPage(root);
    }

    private void showCustomIntentTypes() {
        LinearLayout root = page("按 Intent 类型选择");
        addBack(root, this::showCustomHome);
        for (IntentTypeCatalog.Spec spec : IntentTypeCatalog.all()) {
            root.addView(actionRow(spec.title, spec.description, null,
                    () -> showCustomHandlersForType(spec), null));
        }
        setPage(root);
    }

    private void showCustomHandlersForType(IntentTypeCatalog.Spec spec) {
        LinearLayout root = page(spec.title);
        addBack(root, this::showCustomIntentTypes);
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
                Discovered d = new Discovered(
                        appLabel(ai.applicationInfo) + " · " + spec.title,
                        resolveLabel(ri), appIcon(ai.packageName),
                        ai.packageName, ai.name, spec.type);
                root.addView(customDiscoveredRow(d));
            }
        }
        setPage(root);
    }

    private View customDiscoveredRow(Discovered d) {
        return actionRow(d.label, d.subtitle, d.icon, () -> {
            CustomMenuActionStore.Item item = new CustomMenuActionStore.Item(
                    null, d.label, d.pkg, d.cls, d.type);
            boolean added = CustomMenuActionStore.add(this, item);
            Toast.makeText(this,
                    added ? "已加入 FloatLens 菜单" : "这个入口已经加入过了",
                    Toast.LENGTH_SHORT).show();
            if (added) showCustomHome();
        }, "加入");
    }

    // -----------------------------------------------------------------------------------------
    // Share/process target ordering
    // -----------------------------------------------------------------------------------------

    private void showTargetManager() {
        boolean customized = TargetMenuStore.isCustomized(this, targetMode);
        List<TargetMenuStore.Item> systemItems = discoverTargetItems();
        List<TargetMenuStore.Item> items = TargetMenuStore.mergeWithSystem(this, targetMode, systemItems);

        LinearLayout root = page(isShareTarget()
                ? "自定义分享菜单" : "自定义打开 / 处理菜单");
        TextView note = text(isShareTarget()
                ? (customized
                    ? "列表来源始终是 Android 当前可分享目标；FloatLens 只保存你的排序和隐藏规则。系统新增目标会自动补到末尾。"
                    : "当前直接读取 Android 可分享目标列表。第一次排序或隐藏后，仅由 FloatLens 保存顺序/隐藏规则，不复制另一套来源。")
                : (customized
                    ? "列表来源始终是 Android 当前可处理目标；FloatLens 只保存你的排序和隐藏规则。"
                    : "当前读取 Android 可处理目标列表。第一次排序或隐藏后会保存 FloatLens 顺序。"), 14);
        note.setPadding(0, 0, 0, dp(10));
        root.addView(note);

        Button add = button(isShareTarget() ? "添加已隐藏的分享应用" : "添加已隐藏的处理应用");
        add.setOnClickListener(v -> showTargetAdd());
        root.addView(add);

        Button reset = button("恢复系统读取顺序");
        reset.setEnabled(customized);
        reset.setOnClickListener(v -> {
            TargetMenuStore.reset(this, targetMode);
            Toast.makeText(this, "已恢复当前系统列表顺序", Toast.LENGTH_SHORT).show();
            showTargetManager();
        });
        root.addView(reset);

        TextView current = text("当前显示（" + items.size() + "）", 18);
        current.setPadding(0, dp(18), 0, dp(6));
        root.addView(current);

        if (items.isEmpty()) {
            TextView empty = text("系统当前没有返回可用目标。", 14);
            empty.setAlpha(.72f);
            root.addView(empty);
        } else {
            LinearLayout list = verticalList();
            list.setOnDragListener((v, event) -> onTargetSortDrag(list, event, items));
            for (int i = 0; i < items.size(); i++) {
                TargetMenuStore.Item item = items.get(i);
                final int index = i;
                list.addView(sortableRow(
                        item.key(),
                        item.label,
                        shortClass(item.className),
                        targetIcon(item),
                        index,
                        items.size(),
                        () -> saveCurrentTargetOrder(items),
                        () -> {
                            saveCurrentTargetOrder(items);
                            if (TargetMenuStore.move(this, targetMode, item.key(), -1)) showTargetManager();
                        },
                        () -> {
                            saveCurrentTargetOrder(items);
                            if (TargetMenuStore.move(this, targetMode, item.key(), 1)) showTargetManager();
                        },
                        () -> {
                            saveCurrentTargetOrder(items);
                            TargetMenuStore.remove(this, targetMode, item.key());
                            showTargetManager();
                        }));
            }
            root.addView(list);
        }
        setPage(root);
    }

    private void showTargetAdd() {
        List<TargetMenuStore.Item> discovered = discoverTargetItems();
        List<TargetMenuStore.Item> current = TargetMenuStore.mergeWithSystem(this, targetMode, discovered);
        Set<String> selected = new HashSet<>();
        for (TargetMenuStore.Item item : current) selected.add(item.key());

        LinearLayout root = page(isShareTarget() ? "添加分享应用" : "添加处理应用");
        addBack(root, this::showTargetManager);

        int available = 0;
        for (TargetMenuStore.Item item : discovered) {
            if (selected.contains(item.key())) continue;
            available++;
            root.addView(actionRow(item.label, shortClass(item.className), targetIcon(item), () -> {
                saveCurrentTargetOrder(current);
                if (TargetMenuStore.add(this, targetMode, item)) {
                    Toast.makeText(this, "已加入", Toast.LENGTH_SHORT).show();
                    showTargetManager();
                }
            }, "加入"));
        }
        if (available == 0) {
            TextView none = text("没有可重新加入的系统目标。", 14);
            none.setPadding(0, dp(14), 0, 0);
            none.setAlpha(.72f);
            root.addView(none);
        }
        setPage(root);
    }

    private boolean onTargetSortDrag(LinearLayout list, DragEvent event,
                                     List<TargetMenuStore.Item> snapshot) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof String;
            case DragEvent.ACTION_DRAG_LOCATION:
                return true;
            case DragEvent.ACTION_DROP: {
                Object state = event.getLocalState();
                if (!(state instanceof String key)) return false;
                saveCurrentTargetOrder(snapshot);
                TargetMenuStore.moveTo(this, targetMode, key,
                        dropIndexForY(list, event.getY()));
                showTargetManager();
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                return true;
            default:
                return true;
        }
    }

    private void saveCurrentTargetOrder(List<TargetMenuStore.Item> snapshot) {
        TargetMenuStore.save(this, targetMode, new ArrayList<>(snapshot));
    }

    /** PackageManager remains the source list; FloatLens stores only user order/hide state. */
    private List<TargetMenuStore.Item> discoverTargetItems() {
        Intent base = isShareTarget()
                ? new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "FloatLens")
                : new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, "FloatLens")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);

        List<ResolveInfo> resolved;
        try { resolved = getPackageManager().queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY); }
        catch (Throwable t) { resolved = new ArrayList<>(); }
        if (resolved == null) resolved = new ArrayList<>();
        resolved = new ArrayList<>(resolved);
        resolved.removeIf(ri -> ri == null || ri.activityInfo == null
                || getPackageName().equals(ri.activityInfo.packageName));

        ArrayList<TargetMenuStore.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : resolved) {
            String pkg = ri.activityInfo.packageName;
            String cls = ri.activityInfo.name;
            String key = pkg + "|" + cls;
            if (!seen.add(key)) continue;
            out.add(new TargetMenuStore.Item(resolveLabel(ri), pkg, cls));
        }
        return out;
    }

    // -----------------------------------------------------------------------------------------
    // Shared picker UI and package helpers
    // -----------------------------------------------------------------------------------------

    private View sortableRow(String dragKey,
                             String title,
                             String subtitle,
                             Drawable icon,
                             int index,
                             int total,
                             Runnable beforeDrag,
                             Runnable moveUp,
                             Runnable moveDown,
                             Runnable remove) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));

        TextView handle = text("≡", 24);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription("长按拖动排序");
        handle.setOnLongClickListener(v -> {
            if (beforeDrag != null) beforeDrag.run();
            ClipData clip = ClipData.newPlainText("FloatLens menu item", dragKey);
            return row.startDragAndDrop(clip, new View.DragShadowBuilder(row), dragKey, 0);
        });
        row.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(54)));

        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.setMarginEnd(dp(10));
            row.addView(iv, ip);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15);
        titleView.setSingleLine(true);
        texts.addView(titleView);
        if (subtitle != null && !subtitle.isBlank()) {
            TextView sub = text(subtitle, 12);
            sub.setAlpha(.65f);
            sub.setSingleLine(true);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, dp(54), 1));

        TextView up = sortButton("↑", index > 0);
        up.setContentDescription("上移");
        up.setOnClickListener(v -> moveUp.run());
        row.addView(up, new LinearLayout.LayoutParams(dp(38), dp(44)));

        TextView down = sortButton("↓", index < total - 1);
        down.setContentDescription("下移");
        down.setOnClickListener(v -> moveDown.run());
        row.addView(down, new LinearLayout.LayoutParams(dp(38), dp(44)));

        TextView delete = sortButton("×", true);
        delete.setContentDescription("移除");
        delete.setOnClickListener(v -> remove.run());
        row.addView(delete, new LinearLayout.LayoutParams(dp(40), dp(44)));
        return row;
    }

    private View actionRow(String title,
                           String subtitle,
                           Drawable icon,
                           Runnable action,
                           String sideText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(7), dp(4), dp(7));
        row.setClickable(true);
        row.setFocusable(true);

        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
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

    private LinearLayout verticalList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        return list;
    }

    private LinearLayout page(String title) {
        LinearLayout root = verticalList();
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

    private int dropIndexForY(LinearLayout list, float y) {
        int count = list.getChildCount();
        if (count <= 1) return 0;
        for (int i = 0; i < count; i++) {
            View child = list.getChildAt(i);
            if (y < (child.getTop() + child.getBottom()) / 2f) return i;
        }
        return count - 1;
    }

    private TextView sortButton(String value, boolean enabled) {
        TextView tv = text(value, 20);
        tv.setGravity(Gravity.CENTER);
        tv.setEnabled(enabled);
        tv.setAlpha(enabled ? 1f : .28f);
        tv.setClickable(enabled);
        tv.setFocusable(enabled);
        return tv;
    }

    private PackageManager pm() { return getPackageManager(); }

    private boolean canDirectLaunch(ActivityInfo ai) {
        if (ai == null || !ai.exported || !ai.enabled) return false;
        if (ai.permission == null || ai.permission.isBlank()) return true;
        return checkSelfPermission(ai.permission) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private List<ResolveInfo> query(Intent intent) {
        try {
            List<ResolveInfo> list = pm().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Throwable t) {
            return new ArrayList<>();
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
        return ri == null || ri.activityInfo == null
                ? "应用" : activityLabel(ri.activityInfo);
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
        try { return pm().getApplicationIcon(pkg); }
        catch (Throwable ignored) { return null; }
    }

    private Drawable targetIcon(TargetMenuStore.Item item) {
        try {
            return pm().getActivityIcon(new ComponentName(item.packageName, item.className));
        } catch (Throwable ignored) {
            return appIcon(item.packageName);
        }
    }

    private String shortClass(String name) {
        if (name == null) return "Activity";
        int p = name.lastIndexOf('.');
        return p >= 0 ? name.substring(p + 1) : name;
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

    private boolean isTargetSection() {
        return SECTION_TARGET.equals(section);
    }

    private boolean isShareTarget() {
        return TargetMenuStore.MODE_SHARE.equals(targetMode);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        finish();
    }

    private static final class Discovered {
        final String label, subtitle, pkg, cls, type;
        final Drawable icon;
        Discovered(String label, String subtitle, Drawable icon,
                   String pkg, String cls, String type) {
            this.label = label;
            this.subtitle = subtitle;
            this.icon = icon;
            this.pkg = pkg;
            this.cls = cls;
            this.type = type;
        }
    }
}
