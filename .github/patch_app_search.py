from pathlib import Path

path = Path('app/src/main/java/com/yagay/floatlens/MenuPickerActivity.java')
s = path.read_text(encoding='utf-8')

def replace_between(text, start_marker, next_marker, replacement):
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f'start marker not found: {start_marker}')
    end = text.find(next_marker, start)
    if end < 0:
        raise SystemExit(f'next marker not found: {next_marker}')
    return text[:start] + replacement + text[end:]

custom_apps = '''    private void showCustomApps() {
        LinearLayout root = page("按 App 选择",
                "选择应用后，FloatLens 会读取它可处理的标准 Intent 和可直接启动入口。",
                this::showCustomHome);
        addLocalBack(root, "返回文字操作菜单");

        List<ApplicationInfo> apps;
        try { apps = new ArrayList<>(pm().getInstalledApplications(0)); }
        catch (Throwable t) { apps = new ArrayList<>(); }
        apps.removeIf(a -> a == null || !a.enabled || getPackageName().equals(a.packageName));
        apps.sort(Comparator.comparing(this::appLabel, String.CASE_INSENSITIVE_ORDER));

        AppUi.Section list = AppUi.section(this, "应用", null);
        renderCustomApps(list.body, apps, "");
        addSearchField(root, "搜索应用名称或包名", query ->
                renderCustomApps(list.body, apps, query));
        AppUi.addSection(root, list);
        show(root);
    }

'''
s = replace_between(s,
        '    private void showCustomApps() {\n',
        '    private void showCustomAppActions(',
        custom_apps)

handlers_and_helpers = '''    private void showCustomHandlersForType(IntentTypeCatalog.Spec spec) {
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

'''
s = replace_between(s,
        '    private void showCustomHandlersForType(IntentTypeCatalog.Spec spec) {\n',
        '    private View customDiscoveredRow(',
        handlers_and_helpers)

target_add = '''    private void showTargetAdd() {
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

'''
s = replace_between(s,
        '    private void showTargetAdd() {\n',
        '    private boolean onTargetSortDrag(',
        target_add)

path.write_text(s, encoding='utf-8')
