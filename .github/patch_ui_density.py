from pathlib import Path

appui = Path('app/src/main/java/com/yagay/floatlens/AppUi.java')
s = appui.read_text(encoding='utf-8')

s = s.replace(
    '        row.addView(arrow, new LinearLayout.LayoutParams(dp(c, 30), dp(c, compact ? 40 : 44)));\n',
    '        row.addView(arrow, new LinearLayout.LayoutParams(dp(c, 30), dp(c, compact ? 36 : 44)));\n')

old_toggle = (
    '        SwitchMaterial toggle = new SwitchMaterial(c);\n'
    '        toggle.setUseMaterialThemeColors(true);\n'
    '        toggle.setChecked(checked);\n'
    '        if (listener != null) toggle.setOnCheckedChangeListener(listener);\n'
    '        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));\n')
new_toggle = (
    '        SwitchMaterial toggle = new SwitchMaterial(c);\n'
    '        toggle.setUseMaterialThemeColors(true);\n'
    '        toggle.setChecked(checked);\n'
    '        toggle.setMinHeight(0);\n'
    '        toggle.setMinimumHeight(0);\n'
    '        if (listener != null) toggle.setOnCheckedChangeListener(listener);\n'
    '        row.addView(toggle, new LinearLayout.LayoutParams(-2,\n'
    '                compact ? dp(c, 40) : LinearLayout.LayoutParams.WRAP_CONTENT));\n')
if old_toggle in s:
    s = s.replace(old_toggle, new_toggle, 1)

old_base = (
    '        int verticalPadding = compact ? 2 : 10;\n'
    '        row.setPadding(dp(c, 14), dp(c, verticalPadding), dp(c, 10), dp(c, verticalPadding));\n'
    '        row.setMinimumHeight(dp(c, compact ? 48 : 56));\n')
new_base = (
    '        int verticalPadding = compact ? 0 : 10;\n'
    '        row.setPadding(dp(c, 14), dp(c, verticalPadding), dp(c, 10), dp(c, verticalPadding));\n'
    '        row.setMinimumHeight(dp(c, compact ? 44 : 56));\n')
if old_base in s:
    s = s.replace(old_base, new_base, 1)

marker = (
    '    static LinearLayout settingBlock(Context c) {\n'
    '        LinearLayout block = new LinearLayout(c);\n'
    '        block.setOrientation(LinearLayout.VERTICAL);\n'
    '        block.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));\n'
    '        return block;\n'
    '    }\n')
slider_block = marker + (
    '\n    static LinearLayout sliderBlock(Context c) {\n'
    '        LinearLayout block = new LinearLayout(c);\n'
    '        block.setOrientation(LinearLayout.VERTICAL);\n'
    '        block.setPadding(dp(c, 14), dp(c, 5), dp(c, 14), dp(c, 2));\n'
    '        return block;\n'
    '    }\n')
if 'static LinearLayout sliderBlock(Context c)' not in s:
    if marker not in s:
        raise SystemExit('settingBlock marker not found')
    s = s.replace(marker, slider_block, 1)

appui.write_text(s, encoding='utf-8')

settings = Path('app/src/main/java/com/yagay/floatlens/SettingsActivity.java')
s = settings.read_text(encoding='utf-8')
seek_sig = '    private void seek(LinearLayout parent, String label, String key,\n'
pos = s.find(seek_sig)
if pos < 0:
    raise SystemExit('seek method not found')
end = s.find('\n    private void actionSpinner(', pos)
if end < 0:
    raise SystemExit('seek method end not found')
new_seek = '''    private void seek(LinearLayout parent, String label, String key,
                      int min, int max, int current, String suffix) {
        LinearLayout block = AppUi.sliderBlock(this);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = AppUi.text(this, label, 14, false);
        TextView value = AppUi.caption(this, current + suffix, 13);
        value.setGravity(Gravity.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(value, new LinearLayout.LayoutParams(-2, -2));
        block.addView(top);

        Slider slider = new Slider(this);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(1f);
        slider.setValue(Math.max(min, Math.min(max, current)));
        slider.setMinimumHeight(0);
        slider.setPadding(0, 0, 0, 0);
        slider.addOnChangeListener((s, next, fromUser) -> {
            if (!fromUser) return;
            int intValue = Math.round(next);
            value.setText(intValue + suffix);
            fs.prefs().edit().putInt(key, intValue).apply();
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, AppUi.dp(this, 34));
        sliderLp.topMargin = AppUi.dp(this, -1);
        block.addView(slider, sliderLp);
        AppUi.addRow(parent, block);
    }
'''
s = s[:pos] + new_seek + s[end:]
settings.write_text(s, encoding='utf-8')