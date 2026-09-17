package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

/** Navigation/lifecycle host for focused FloatLens settings pages. */
public class SettingsActivity extends AppCompatActivity {
    private static final String EXTRA_SECTION = "settings_section";

    public static final int SECTION_HOME = 0;
    public static final int SECTION_ICON = 1;
    public static final int SECTION_GESTURE = 2;
    public static final int SECTION_CAPTURE = 3;
    public static final int SECTION_ENVIRONMENT = 4;
    public static final int SECTION_ACTIONS = 5;
    public static final int SECTION_PRIVILEGE = 6;

    static final int REQUEST_CUSTOM_ICON = 401;
    static final int REQUEST_SLIDE_ICONS = 402;

    private FloatSettings fs;

    public static Intent intent(Context c, int section) {
        return new Intent(c, SettingsActivity.class).putExtra(EXTRA_SECTION, section);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        fs = new FloatSettings(this);
        int section = getIntent().getIntExtra(EXTRA_SECTION, SECTION_HOME);
        LinearLayout root;
        switch (section) {
            case SECTION_ICON -> root = SettingsIconPage.build(this, fs);
            case SECTION_GESTURE -> root = SettingsGesturePage.build(this, fs);
            case SECTION_CAPTURE -> root = SettingsCapturePage.build(this, fs);
            case SECTION_ENVIRONMENT -> root = SettingsEnvironmentPage.build(this, fs);
            case SECTION_ACTIONS -> root = SettingsActionsPage.build(this, fs);
            case SECTION_PRIVILEGE -> root = PrivilegeSettingsPanel.build(this, fs);
            default -> root = buildHomePage();
        }
        setContentView(AppUi.scrollPage(this, root));
    }

    private LinearLayout buildHomePage() {
        LinearLayout root = AppUi.pageRoot(this, "设置",
                "按功能进入独立页面，修改后立即生效。" );
        AppUi.Section categories = AppUi.section(this, "分类", null);
        addCategory(categories.body, "悬浮图标", "外观、贴边、显示行为", SECTION_ICON);
        addCategory(categories.body, "手势与轨迹", "点击、长按、滑动阈值与轨迹", SECTION_GESTURE);
        addCategory(categories.body, "截图与 OCR", "截图来源、OCR 引擎、模型与语言", SECTION_CAPTURE);
        addCategory(categories.body, "环境与显示", "键盘避让、智能入口、按应用隐藏", SECTION_ENVIRONMENT);
        addCategory(categories.body, "高级权限", "Root / LSPosed 可选增强、状态与失败回退", SECTION_PRIVILEGE);
        addCategory(categories.body, "手势动作映射", "给每种手势分配动作", SECTION_ACTIONS);
        AppUi.addSection(root, categories);
        return root;
    }

    private void addCategory(LinearLayout parent, String title, String subtitle, int section) {
        AppUi.addRow(parent, AppUi.navRow(this, title, subtitle,
                () -> startActivity(intent(this, section))));
    }

    private void persistReadPermission(android.net.Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {}
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CUSTOM_ICON && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            persistReadPermission(data.getData());
            fs.selectCustomIcon(data.getData().toString());
        } else if (requestCode == REQUEST_SLIDE_ICONS && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    android.net.Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri == null) continue;
                    uris.add(uri.toString());
                    persistReadPermission(uri);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData().toString());
                persistReadPermission(data.getData());
            }
            if (!uris.isEmpty()) fs.selectSlideIcons(String.join("|", uris));
        }
    }
}
