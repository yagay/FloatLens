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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Unified menu manager using the same visual hierarchy as the rest of FloatLens. */
public final class MenuPickerActivity extends AppCompatActivity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_MODE = "mode";
    public static final String SECTION_CUSTOM = "custom";
    public static final String SECTION_TARGET = "target";

    private String section;
    private String targetMode;
    private Runnable localBackAction;

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
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });
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
        LinearLayout root = page("文字操作菜单",
                "管理选中文字后可调用的应用和 Intent 操作。", null);

        AppUi.Section add = AppUi.section(this, "添加操作",
                "不需要手动填写包名、Activity、Action 或 MIME。" );
        AppUi.addRow(add.body, AppUi.navRow(this,
                "按 App 选择",
                "先选择应用，再查看它可用的入口",
                this::showCustomApps));
        AppUi.addRow(add.body, AppUi.navRow(this,
                "按 Intent 类型选择",
                "先选择操作类型，再选择可以处理它的应用",
                this::showCustomIntentTypes));
        AppUi.addSection(root, add);

        List<CustomMenuActionStore.Item> items = CustomMenuActionStore.load(this);
        AppUi.Section current = AppUi.section(this,
                "当前菜单 · " + items.size() + " 项",
                "长按 ≡ 可拖动排序，也可以使用右侧按钮精确移动。" );
        if (items.isEmpty()) {
            addEmpty(current.body, "还没有自定义文字操作");
        } else {
            current.body.setOnDragListener((v, event) -> onCustomSortDrag(current.body, event));
            for (int i = 0; i < items.size(); i++) {
                CustomMenuActionStore.Item item = items.get(i);
                final int index = i;
                AppUi.addRow(current.body, sortableRow(
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
        }
        AppUi.addSection(root, current);
        show(root);
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
        LinearLayout root = page("按 App 选择",
                "选择应用后，FloatLens 会读取它可处理的标准 Intent 和可直接启动入口。",
                this::showCustomHome);
        addLocalBack(root, "返回文字操作菜单");

        List<ApplicationInfo> apps;
        try { apps = new ArrayList<>(pm().getInstalledApplications(0)); }
        catch (Throwable t) { apps = new ArrayList<>(); }
        apps.removeIf(a -> a == null || !a.enabled || getPackageName().equals(a.packageName));
        apps.sort(Comparator.comparing(this::appLabel, String.CASE_INSENSITIVE_ORDER));

        final List<ApplicationInfo> appList = apps;
        AppUi.Section list = AppUi.section(this, "应用", null);
        renderCustomApps(list.body, appList, "");
        addSearchField(root, "搜索应用名称或包名", query ->
                renderCustomApps(list.body, appList, query));
        AppUi.addSection(root, list);
        show(root);
    }

    private void showCustomAppActions(String pkg, String appLabel) {
        LinearLayout root = page(appLabel,
                "标准 Intent 入口优先显示，后面再列出其他可直接启动的 exported Activity。",
                this::showCustomApps);
        addLocalBack(root, "返回应用列表");

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

        AppUi.Section entries = AppUi.section(this, "可用入口 · " + all.size() + " 个", null);
        if (all.isEmpty()) {
            addEmpty(entries.body, "没有发现可从 FloatLens 调用的入口");
        } else {
            for (Discovered d : all) AppUi.addRow(entries.body, customDiscoveredRow(d));
        }
        AppUi.addSection(root, entries);
        show(root);
    }

    private void showCustomIntentTypes() {
        LinearLayout root = page("按 Intent 类型选择",
                "先确定操作类型，再从系统确认可以处理它的应用中选择。",
                this::showCustomHome);
        addLocalBack(root, "返回文字操作菜单");

        AppUi.Section types = AppUi.section(this, "操作类型", null);
        for (IntentTypeCatalog.Spec spec : IntentTypeCatalog.all()) {
            AppUi.addRow(types.body, actionRow(spec.title, spec.description, null,
                    () -> showCustomHandlersForType(spec), null));
        }
        AppUi.addSection(root, types);
        show(root);
    }

    private void showCustomHandlersForType(IntentTypeCatalog.Spec spec) {
        LinearLayout root = page(spec.title,
                spec.description + " · 只显示系统确认能处理这个 Intent 的应用入口。",
                this::showCustomIntentTypes);
        addLocalBack(root, "返回 Intent 类型");

        List<ResolveInfo> handlers = query(spec.probeIntent());
        handlers.removeIf(ri -> ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName));
        handlers.sort(Comparator.comparing(this::resolveAppThenActivityLabel, String.CASE_INSENSITIVE_ORDER));

        AppUi.Section entries = AppUi.section(this, "可用应用", null);
        renderIntentHandlers(entries.body, handlers, spec, "");
        addSearchField(root, "搜索应用名称、包名或 Activity", query ->
                renderIntentHandlers(entries.body, handlers, spec, query));
        AppUi.addSection(root, entries);
        show(root);
    }

    private android.widget.EditText addSearchField(LinearLayout root,
                                                   String hint,
                                                   java.util.function.Consumer<String> onQuery) {
        LinearLayout box = AppUi.settingBlock(this);
        box.setPadding(AppUi.dp(this, 2), AppUi.dp(this, 2),
                AppUi.dp(this, 2), AppUi.dp(this, 7));

        android.widget.EditText input = new android.widget.EditText(this);
        AppUi.styleInput(this, input);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = AppUi.dp(this, 3);
        root.addView(box, lp);

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable editable) {
                if (onQuery != null) onQuery.accept(editable == null ? "" : editable.toString());
            }
        });
        return input;
    }

    private int renderCustomApps(LinearLayout body,
                                 List<ApplicationInfo> apps,
                                 String query) {
        body.removeAllViews();
        int shown = 0;
        for (ApplicationInfo app : apps) {
            String label = appLabel(app);
            String pkg = app.packageName;
            if (!matchesSearch(query, label, pkg)) continue;
            Drawable icon = null;
            try { icon = app.loadIcon(pm()); } catch (Throwable ignored) {}
            AppUi.addRow(body,
                    actionRow(label, pkg, icon, () -> showCustomAppActions(pkg, label), null));
            shown++;
        }
        if (shown == 0) addEmpty(body, "没有匹配的应用");
        return shown;
    }

    private int renderIntentHandlers(LinearLayout body,
                                     List<ResolveInfo> handlers,
                                     IntentTypeCatalog.Spec spec,
                                     String query) {
        body.removeAllViews();
        int shown = 0;
        for (ResolveInfo ri : handlers) {
            if (ri == null || ri.activityInfo == null) continue;
            ActivityInfo ai = ri.activityInfo;
            String appName = appLabel(ai.applicationInfo);
            String activityName = resolveLabel(ri);
            if (!matchesSearch(query,
                    appName,
                    ai.packageName,
                    activityName,
                    ai.name,
                    shortClass(ai.name))) continue;
            Discovered d = new Discovered(
                    appName + " · " + spec.title,
                    activityName, appIcon(ai.packageName),
                    ai.packageName, ai.name, spec.type);
            AppUi.addRow(body, customDiscoveredRow(d));
            shown++;
        }
        if (shown == 0) {
            addEmpty(body, query == null || query.isBlank()
                    ? "没有找到可处理此 Intent 的应用"
                    : "没有匹配的应用或 Activity");
        }
        return shown;
    }

    private int renderTargetAdd(LinearLayout body,
                                List<TargetMenuStore.Item> available,
                                List<TargetMenuStore.Item> current,
                                String query) {
        body.removeAllViews();
        int shown = 0;
        for (TargetMenuStore.Item item : available) {
            if (!matchesSearch(query,
                    item.label,
                    item.packageName,
                    item.className,
                    shortClass(item.className))) continue;
            AppUi.addRow(body,
                    actionRow(item.label, shortClass(item.className), targetIcon(item), () -> {
                        saveCurrentTargetOrder(current);
                        if (TargetMenuStore.add(this, targetMode, item)) {
                            Toast.makeText(this, "已加入", Toast.LENGTH_SHORT).show();
                            showTargetManager();
                        }
                    }, "加入"));
            shown++;
        }
        if (shown == 0) {
            addEmpty(body, available.isEmpty()
                    ? "没有可重新加入的系统目标"
                    : "没有匹配的应用或组件");
        }
        return shown;
    }

    private boolean matchesSearch(String query, String... values) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return true;
        if (values == null) return false;
        for (String value : values) {
            if (value != null && value.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
        }
        return false;
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
        localBackAction = null;
        boolean customized = TargetMenuStore.isCustomized(this, targetMode);
        List<TargetMenuStore.Item> systemItems = discoverTargetItems();
        List<TargetMenuStore.Item> items = TargetMenuStore.mergeWithSystem(this, targetMode, systemItems);

        LinearLayout root = page(isShareTarget() ? "分享菜单" : "打开 / 处理菜单",
                isShareTarget()
                        ? "来源始终是 Android 当前可分享目标，FloatLens 只保存排序和隐藏规则。"
                        : "来源始终是 Android 当前可处理目标，FloatLens 只保存排序和隐藏规则。",
                null);

        AppUi.Section tools = AppUi.section(this, "管理", null);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(AppUi.dp(this, 14), AppUi.dp(this, 8),
                AppUi.dp(this, 14), AppUi.dp(this, 8));
        MaterialButton add = AppUi.secondaryButton(this,
                isShareTarget() ? "添加已隐藏项" : "添加已隐藏项");
        add.setOnClickListener(v -> showTargetAdd());
        MaterialButton reset = AppUi.secondaryButton(this, "恢复系统顺序");
        reset.setEnabled(customized);
        reset.setOnClickListener(v -> {
            TargetMenuStore.reset(this, targetMode);
            Toast.makeText(this, "已恢复当前系统列表顺序", Toast.LENGTH_SHORT).show();
            showTargetManager();
        });
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(0, -2, 1f);
        aLp.setMarginEnd(AppUi.dp(this, 6));
        buttons.addView(add, aLp);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, -2, 1f);
        rLp.setMarginStart(AppUi.dp(this, 6));
        buttons.addView(reset, rLp);
        AppUi.addRow(tools.body, buttons);
        AppUi.addSection(root, tools);

        AppUi.Section current = AppUi.section(this,
                "当前显示 · " + items.size() + " 项",
                "长按 ≡ 拖动排序；移除只是从 FloatLens 菜单隐藏，不会修改系统应用。" );
        if (items.isEmpty()) {
            addEmpty(current.body, "系统当前没有返回可用目标");
        } else {
            current.body.setOnDragListener((v, event) -> onTargetSortDrag(current.body, event, items));
            for (int i = 0; i < items.size(); i++) {
                TargetMenuStore.Item item = items.get(i);
                final int index = i;
                AppUi.addRow(current.body, sortableRow(
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
        }
        AppUi.addSection(root, current);
        show(root);
    }

    private void showTargetAdd() {
        List<TargetMenuStore.Item> discovered = discoverTargetItems();
        List<TargetMenuStore.Item> current = TargetMenuStore.mergeWithSystem(this, targetMode, discovered);
        Set<String> selected = new HashSet<>();
        for (TargetMenuStore.Item item : current) selected.add(item.key());

        LinearLayout root = page(isShareTarget() ? "添加分享应用" : "添加处理应用",
                "这里只列出之前从 FloatLens 菜单隐藏、但系统仍然可用的目标。",
                this::showTargetManager);
        addLocalBack(root, "返回当前菜单");

        ArrayList<TargetMenuStore.Item> available = new ArrayList<>();
        for (TargetMenuStore.Item item : discovered) {
            if (!selected.contains(item.key())) available.add(item);
        }

        AppUi.Section list = AppUi.section(this, "可重新加入", null);
        renderTargetAdd(list.body, available, current, "");
        addSearchField(root, "搜索应用名称、包名或组件", query ->
                renderTargetAdd(list.body, available, current, query));
        AppUi.addSection(root, list);
        show(root);
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
                TargetMenuStore.moveTo(this, targetMode, key, dropIndexForY(list, event.getY()));
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
        LinearLayout row = AppUi.baseRow(this);
        row.setPadding(AppUi.dp(this, 6), AppUi.dp(this, 7),
                AppUi.dp(this, 6), AppUi.dp(this, 7));

        TextView handle = AppUi.text(this, "≡", 22, false);
        handle.setTextColor(AppUi.textSecondary(this));
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription("长按拖动排序");
        handle.setOnLongClickListener(v -> {
            if (beforeDrag != null) beforeDrag.run();
            ClipData clip = ClipData.newPlainText("FloatLens menu item", dragKey);
            return row.startDragAndDrop(clip, new View.DragShadowBuilder(row), dragKey, 0);
        });
        row.addView(handle, new LinearLayout.LayoutParams(dp(36), dp(52)));

        addIcon(row, icon, 34, 10);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = AppUi.text(this, title, 14, false);
        titleView.setSingleLine(true);
        texts.addView(titleView);
        if (subtitle != null && !subtitle.isBlank()) {
            TextView sub = AppUi.caption(this, subtitle, 11);
            sub.setSingleLine(true);
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, dp(52), 1f));

        TextView up = sortButton("↑", index > 0);
        up.setContentDescription("上移");
        up.setOnClickListener(v -> { if (moveUp != null) moveUp.run(); });
        row.addView(up, new LinearLayout.LayoutParams(dp(34), dp(42)));

        TextView down = sortButton("↓", index < total - 1);
        down.setContentDescription("下移");
        down.setOnClickListener(v -> { if (moveDown != null) moveDown.run(); });
        row.addView(down, new LinearLayout.LayoutParams(dp(34), dp(42)));

        TextView delete = sortButton("×", true);
        delete.setContentDescription("移除");
        delete.setOnClickListener(v -> { if (remove != null) remove.run(); });
        row.addView(delete, new LinearLayout.LayoutParams(dp(36), dp(42)));
        return row;
    }

    private View actionRow(String title,
                           String subtitle,
                           Drawable icon,
                           Runnable action,
                           String sideText) {
        LinearLayout row = AppUi.baseRow(this);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(AppUi.rowBackground(this));

        addIcon(row, icon, 36, 12);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView a = AppUi.text(this, title, 15, false);
        a.setSingleLine(true);
        texts.addView(a);
        if (subtitle != null && !subtitle.isBlank()) {
            TextView b = AppUi.caption(this, subtitle, 12);
            b.setSingleLine(true);
            b.setPadding(0, dp(2), dp(8), 0);
            texts.addView(b);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        if (sideText != null) {
            MaterialButton side = AppUi.compactButton(this, sideText);
            side.setOnClickListener(v -> { if (action != null) action.run(); });
            row.addView(side, new LinearLayout.LayoutParams(-2, -2));
        } else {
            TextView arrow = AppUi.text(this, "›", 24, false);
            arrow.setTextColor(AppUi.textSecondary(this));
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        }
        row.setOnClickListener(v -> { if (action != null) action.run(); });
        return row;
    }

    private void addIcon(LinearLayout row, Drawable icon, int sizeDp, int endMarginDp) {
        if (icon == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        ip.setMarginEnd(dp(endMarginDp));
        row.addView(iv, ip);
    }

    private void addEmpty(LinearLayout parent, String message) {
        LinearLayout row = AppUi.baseRow(this);
        TextView empty = AppUi.caption(this, message, 13);
        empty.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        AppUi.addRow(parent, row);
    }

    private LinearLayout page(String title, String subtitle, Runnable backAction) {
        localBackAction = backAction;
        return AppUi.pageRoot(this, title, subtitle);
    }

    private void show(LinearLayout root) {
        setContentView(AppUi.scrollPage(this, root));
    }

    private void addLocalBack(LinearLayout root, String label) {
        MaterialButton back = AppUi.secondaryButton(this, "‹ " + label);
        back.setOnClickListener(v -> {
            Runnable action = localBackAction;
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = AppUi.dp(this, 12);
        root.addView(back, lp);
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
        TextView tv = AppUi.text(this, value, 19, false);
        tv.setGravity(Gravity.CENTER);
        tv.setEnabled(enabled);
        tv.setAlpha(enabled ? 1f : .25f);
        tv.setClickable(enabled);
        tv.setFocusable(enabled);
        tv.setBackground(AppUi.rowBackground(this));
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

    private boolean isTargetSection() {
        return SECTION_TARGET.equals(section);
    }

    private boolean isShareTarget() {
        return TargetMenuStore.MODE_SHARE.equals(targetMode);
    }

    private int dp(int value) {
        return AppUi.dp(this, value);
    }

    private void handleBackNavigation() {
        Runnable action = localBackAction;
        if (action != null) {
            localBackAction = null;
            action.run();
        } else {
            finish();
        }
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
