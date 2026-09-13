package com.yagay.floatlens;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Online catalogue for separately downloadable local dictionaries. */
public final class DictionaryCatalogActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView summary;
    private final Map<String, Row> rows = new HashMap<>();
    private volatile boolean destroyed;

    private static final class Row {
        TextView status;
        ProgressBar progress;
        CheckBox enabled;
        Button action;
        Button delete;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("在线词库中心");
        setContentView(buildUi());
        refreshAll();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("在线词库中心");
        title.setTextSize(23);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("这里只列入来源公开、下载稳定并且 FloatLens 能自动导入的词库。词典数据不会打包进 APK；每个词库都可以单独下载、启用、更新或删除。");
        intro.setTextSize(14);
        intro.setAlpha(0.78f);
        intro.setPadding(0, dp(6), 0, dp(12));
        root.addView(intro);

        summary = new TextView(this);
        summary.setTextSize(15);
        summary.setPadding(0, 0, 0, dp(10));
        root.addView(summary);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(-1, -2));

        for (DictionaryLibraryManager.CatalogItem item : DictionaryLibraryManager.catalog()) {
            addRow(item);
        }

        TextView note = new TextView(this);
        note.setText("来源说明\n• ECDICT：通过 GitHub Releases 自动查找最新版 StarDict。\n• FreeDict：通过 freedict-database.json 官方 API 自动获取最新版。\n• WikDict：直接使用公开 StarDict 下载目录。\n• CC-CEDICT：直接使用 MDBG 发布的最新 UTF-8 数据。\n\n后续可以继续增加兼容格式和公开来源；来源不明或版权状态不清楚的商业词典不会放进内置下载列表。");
        note.setTextSize(13);
        note.setAlpha(0.68f);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void addRow(DictionaryLibraryManager.CatalogItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(12);
        list.addView(card, cp);

        TextView name = new TextView(this);
        name.setText(item.title);
        name.setTextSize(18);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(name);

        TextView desc = new TextView(this);
        desc.setText(item.description + "\n" + item.direction + " · " + item.license);
        desc.setTextSize(13);
        desc.setAlpha(0.76f);
        desc.setPadding(0, dp(3), 0, dp(5));
        card.addView(desc);

        Row row = new Row();
        row.status = new TextView(this);
        row.status.setTextSize(14);
        card.addView(row.status);

        row.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        row.progress.setMax(100);
        row.progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(8));
        pp.topMargin = dp(5);
        pp.bottomMargin = dp(5);
        card.addView(row.progress, pp);

        row.enabled = new CheckBox(this);
        row.enabled.setText("启用这个词典");
        row.enabled.setOnCheckedChangeListener((button, checked) -> {
            if (button.isPressed()) DictionaryLibraryManager.setEnabled(this, item.id, checked);
        });
        card.addView(row.enabled);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        row.action = new Button(this);
        row.delete = new Button(this);
        row.delete.setText("删除");
        buttons.addView(row.action, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(row.delete, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(buttons);

        row.action.setOnClickListener(v -> install(item, row));
        row.delete.setOnClickListener(v -> confirmDelete(item));
        rows.put(item.id, row);
    }

    private void install(DictionaryLibraryManager.CatalogItem item, Row row) {
        if (DictionaryLibraryManager.isBusy()) {
            Toast.makeText(this, "已有词典正在下载或导入", Toast.LENGTH_SHORT).show();
            return;
        }
        row.progress.setVisibility(View.VISIBLE);
        row.progress.setProgress(0);
        row.status.setText("准备下载…");
        refreshButtons();
        DictionaryLibraryManager.install(this, item, new DictionaryLibraryManager.Callback() {
            @Override public void onProgress(String stage, int percent) {
                if (destroyed) return;
                row.progress.setVisibility(View.VISIBLE);
                row.progress.setProgress(percent);
                row.status.setText(stage + " · " + percent + "%");
            }

            @Override public void onSuccess() {
                if (destroyed) return;
                Toast.makeText(DictionaryCatalogActivity.this, item.title + " 已安装", Toast.LENGTH_SHORT).show();
                refreshAll();
            }

            @Override public void onFailure(String message) {
                if (destroyed) return;
                Toast.makeText(DictionaryCatalogActivity.this,
                        item.title + " 安装失败：" + message, Toast.LENGTH_LONG).show();
                refreshAll();
            }
        });
    }

    private void confirmDelete(DictionaryLibraryManager.CatalogItem item) {
        if (!DictionaryLibraryManager.isInstalled(this, item.id)) return;
        new AlertDialog.Builder(this)
                .setTitle("删除 “" + item.title + "”？")
                .setMessage("只删除这个单独下载的本地词典，不会影响其他词典和 FloatLens APK。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    DictionaryLibraryManager.delete(this, item.id);
                    refreshAll();
                })
                .show();
    }

    private void refreshAll() {
        if (summary == null) return;
        int installed = DictionaryLibraryManager.installedCount(this);
        summary.setText("已安装 " + installed + " / " + DictionaryLibraryManager.catalog().size() + " 个本地词典");
        for (DictionaryLibraryManager.CatalogItem item : DictionaryLibraryManager.catalog()) {
            Row row = rows.get(item.id);
            if (row == null) continue;
            boolean ready = DictionaryLibraryManager.isInstalled(this, item.id);
            boolean busy = DictionaryLibraryManager.isBusy(item.id);
            long bytes = DictionaryLibraryManager.installedBytes(this, item.id);
            if (busy) {
                row.status.setText("正在下载或导入…");
                row.progress.setVisibility(View.VISIBLE);
            } else if (ready) {
                row.status.setText(String.format(Locale.ROOT, "已安装 · %.1f MB", bytes / 1024d / 1024d));
                row.progress.setVisibility(View.GONE);
            } else {
                row.status.setText("未安装");
                row.progress.setVisibility(View.GONE);
            }
            row.enabled.setEnabled(ready && !busy);
            row.enabled.setChecked(ready && DictionaryLibraryManager.isEnabled(this, item.id));
            row.action.setText(ready ? "更新" : "下载并安装");
            row.action.setEnabled(!DictionaryLibraryManager.isBusy());
            row.delete.setEnabled(ready && !DictionaryLibraryManager.isBusy());
        }
        refreshButtons();
    }

    private void refreshButtons() {
        boolean anyBusy = DictionaryLibraryManager.isBusy();
        for (DictionaryLibraryManager.CatalogItem item : DictionaryLibraryManager.catalog()) {
            Row row = rows.get(item.id);
            if (row == null) continue;
            boolean ready = DictionaryLibraryManager.isInstalled(this, item.id);
            row.action.setEnabled(!anyBusy);
            row.delete.setEnabled(ready && !anyBusy);
            row.enabled.setEnabled(ready && !anyBusy);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
