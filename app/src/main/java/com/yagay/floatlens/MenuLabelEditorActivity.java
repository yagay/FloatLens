package com.yagay.floatlens;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Edits FloatLens-only display names without changing package/activity identity. */
public final class MenuLabelEditorActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        rebuild();
    }

    private void rebuild() {
        LinearLayout root = AppUi.pageRoot(this, "菜单显示名称",
                "名称只影响 FloatLens 中的显示，不会修改系统应用名称或调用目标。" );

        addCustomSection(root);
        addTargetSection(root, TargetMenuStore.MODE_SHARE, "分享菜单");
        addTargetSection(root, TargetMenuStore.MODE_PROCESS, "打开 / 处理菜单");

        setContentView(AppUi.scrollPage(this, root));
    }

    private void addCustomSection(LinearLayout root) {
        List<CustomMenuActionStore.Item> items = CustomMenuActionStore.load(this);
        AppUi.Section section = AppUi.section(this,
                "文字操作菜单 · " + items.size() + " 项",
                "自定义操作名称太长时，可以在这里改成更短的显示名称。" );
        if (items.isEmpty()) {
            addEmpty(section.body, "还没有自定义文字操作");
        } else {
            for (CustomMenuActionStore.Item item : items) {
                addRenameRow(section.body,
                        item.label,
                        CustomMenuActionStore.typeLabel(item.type),
                        () -> showRenameDialog(
                                item.label,
                                null,
                                name -> {
                                    if (CustomMenuActionStore.rename(this, item.id, name)) {
                                        Toast.makeText(this, "名称已修改", Toast.LENGTH_SHORT).show();
                                        rebuild();
                                    }
                                },
                                null));
            }
        }
        AppUi.addSection(root, section);
    }

    private void addTargetSection(LinearLayout root, String mode, String title) {
        List<TargetMenuStore.Item> system = discoverTargets(mode);
        AppUi.Section section = AppUi.section(this,
                title + " · " + system.size() + " 项",
                "可修改 FloatLens 显示名称，也可以恢复 Android 当前返回的系统名称。" );

        if (system.isEmpty()) {
            addEmpty(section.body, "系统当前没有返回可用目标");
        } else {
            for (TargetMenuStore.Item item : system) {
                String display = TargetMenuStore.displayLabel(
                        this, mode, item.key(), item.label);
                String subtitle = display.equals(item.label)
                        ? item.packageName
                        : "系统名称：" + item.label;
                addRenameRow(section.body, display, subtitle,
                        () -> showRenameDialog(
                                display,
                                item.label,
                                name -> {
                                    if (TargetMenuStore.rename(this, mode, item.key(), name)) {
                                        Toast.makeText(this, "名称已修改", Toast.LENGTH_SHORT).show();
                                        rebuild();
                                    }
                                },
                                () -> {
                                    if (TargetMenuStore.clearAlias(this, mode, item.key())) {
                                        Toast.makeText(this, "已恢复系统名称", Toast.LENGTH_SHORT).show();
                                        rebuild();
                                    }
                                }));
            }
        }
        AppUi.addSection(root, section);
    }

    private List<TargetMenuStore.Item> discoverTargets(String mode) {
        Intent base = TargetMenuStore.MODE_PROCESS.equals(mode)
                ? new Intent(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, "FloatLens")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                : new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "FloatLens");

        List<ResolveInfo> resolved;
        try {
            resolved = getPackageManager().queryIntentActivities(
                    base, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (Throwable t) {
            resolved = new ArrayList<>();
        }
        if (resolved == null) resolved = new ArrayList<>();

        ArrayList<TargetMenuStore.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            String pkg = ri.activityInfo.packageName;
            String cls = ri.activityInfo.name;
            String key = pkg + "|" + cls;
            if (!seen.add(key)) continue;
            CharSequence label;
            try { label = ri.loadLabel(getPackageManager()); }
            catch (Throwable ignored) { label = cls; }
            out.add(new TargetMenuStore.Item(
                    label == null ? cls : label.toString(), pkg, cls));
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void addRenameRow(LinearLayout parent,
                              String title,
                              String subtitle,
                              Runnable editAction) {
        LinearLayout row = AppUi.baseRow(this);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = AppUi.text(this, title, 14, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        if (subtitle != null && !subtitle.isBlank()) {
            TextView sub = AppUi.caption(this, subtitle, 11);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(0, AppUi.dp(this, 2), AppUi.dp(this, 8), 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        MaterialButton edit = AppUi.compactButton(this, "修改");
        edit.setOnClickListener(v -> {
            if (editAction != null) editAction.run();
        });
        row.addView(edit, new LinearLayout.LayoutParams(-2, -2));
        AppUi.addRow(parent, row);
    }

    private void addEmpty(LinearLayout parent, String message) {
        LinearLayout row = AppUi.baseRow(this);
        TextView empty = AppUi.caption(this, message, 13);
        row.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        AppUi.addRow(parent, row);
    }

    private void showRenameDialog(String current,
                                  String systemLabel,
                                  java.util.function.Consumer<String> onSave,
                                  Runnable onReset) {
        EditText input = new EditText(this);
        AppUi.styleInput(this, input);
        input.setSingleLine(true);
        input.setText(current == null ? "" : current);
        input.setSelectAllOnFocus(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AppUi.dp(this, 20), AppUi.dp(this, 4),
                AppUi.dp(this, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        if (systemLabel != null && !systemLabel.isBlank()) {
            TextView system = AppUi.caption(this, "系统名称：" + systemLabel, 11);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = AppUi.dp(this, 6);
            box.addView(system, lp);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("修改显示名称")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText() == null
                            ? "" : input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (onSave != null) onSave.accept(name);
                });
        if (onReset != null) {
            builder.setNeutralButton("恢复系统名称", (dialog, which) -> onReset.run());
        }
        builder.show();
    }
}
