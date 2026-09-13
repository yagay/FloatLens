from pathlib import Path

path = Path('app/src/main/java/com/yagay/floatlens/MenuPickerActivity.java')
s = path.read_text(encoding='utf-8')
old = '''        AppUi.Section list = AppUi.section(this, "应用", null);
        renderCustomApps(list.body, apps, "");
        addSearchField(root, "搜索应用名称或包名", query ->
                renderCustomApps(list.body, apps, query));
'''
new = '''        final List<ApplicationInfo> appList = apps;
        AppUi.Section list = AppUi.section(this, "应用", null);
        renderCustomApps(list.body, appList, "");
        addSearchField(root, "搜索应用名称或包名", query ->
                renderCustomApps(list.body, appList, query));
'''
if old not in s:
    raise SystemExit('app picker block not found')
s = s.replace(old, new, 1)
path.write_text(s, encoding='utf-8')
