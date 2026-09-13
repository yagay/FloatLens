package com.yagay.floatlens;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** Dictionary settings: generic downloadable local libraries plus online Wiktionary. */
public final class DictionarySettingsActivity extends AppCompatActivity {
    private TextView libraryStatus;
    private TextView legacyStatus;
    private Button legacyDelete;
    private CheckBox onlineEnabled;
    private Spinner queryMode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("词典设置");
        setContentView(buildUi());
        refreshState();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        sectionTitle(root, "本地词典");
        TextView note = new TextView(this);
        note.setText("本地词典改为通用词库中心管理，不再把 ECCEDICT 当成特殊依赖。每个词库都独立下载、启用、更新和删除，数据不打包进 APK。");
        note.setTextSize(14);
        note.setAlpha(0.76f);
        note.setPadding(0, dp(6), 0, dp(10));
        root.addView(note);

        libraryStatus = new TextView(this);
        libraryStatus.setTextSize(16);
        libraryStatus.setPadding(0, 0, 0, dp(6));
        root.addView(libraryStatus);

        Button catalog = new Button(this);
        catalog.setText("打开在线词库中心");
        catalog.setOnClickListener(v -> startActivity(new Intent(this, DictionaryCatalogActivity.class)));
        root.addView(catalog, new LinearLayout.LayoutParams(-1, -2));

        TextView available = new TextView(this);
        available.setText("可选来源：ECDICT、WikDict 英→中、WikDict 中→英、FreeDict 英→中、CC-CEDICT 中→英。后续新增公开来源时不需要重写查询界面。");
        available.setTextSize(13);
        available.setAlpha(0.68f);
        available.setPadding(0, dp(8), 0, 0);
        root.addView(available);

        legacyStatus = new TextView(this);
        legacyStatus.setTextSize(13);
        legacyStatus.setAlpha(0.7f);
        legacyStatus.setPadding(0, dp(12), 0, dp(4));
        root.addView(legacyStatus);

        legacyDelete = new Button(this);
        legacyDelete.setText("删除旧版 ECCEDICT 数据");
        legacyDelete.setOnClickListener(v -> confirmDeleteLegacy());
        root.addView(legacyDelete, new LinearLayout.LayoutParams(-1, -2));

        sectionTitle(root, "在线词典 · Wiktionary");
        TextView onlineNote = new TextView(this);
        onlineNote.setText("无需下载、无需 API Key。英文查询使用中文 Wiktionary；中文查询使用英文 Wiktionary。查询文字会发送到 Wikimedia 服务器。");
        onlineNote.setTextSize(14);
        onlineNote.setAlpha(0.76f);
        onlineNote.setPadding(0, dp(6), 0, dp(8));
        root.addView(onlineNote);

        onlineEnabled = new CheckBox(this);
        onlineEnabled.setText("启用在线词典");
        onlineEnabled.setChecked(OnlineDictionaryClient.isEnabled(this));
        onlineEnabled.setOnCheckedChangeListener((button, checked) -> {
            OnlineDictionaryClient.setEnabled(this, checked);
            if (!checked && queryMode != null
                    && queryMode.getSelectedItemPosition() == OnlineDictionaryClient.MODE_ONLINE_ONLY) {
                queryMode.setSelection(OnlineDictionaryClient.MODE_LOCAL_FIRST);
                OnlineDictionaryClient.setMode(this, OnlineDictionaryClient.MODE_LOCAL_FIRST);
                Toast.makeText(this, "已切换为本地优先", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(onlineEnabled);

        TextView modeLabel = new TextView(this);
        modeLabel.setText("查询模式");
        modeLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(modeLabel);

        queryMode = new Spinner(this);
        String[] modes = {
                OnlineDictionaryClient.modeLabel(OnlineDictionaryClient.MODE_LOCAL_FIRST),
                OnlineDictionaryClient.modeLabel(OnlineDictionaryClient.MODE_BOTH),
                OnlineDictionaryClient.modeLabel(OnlineDictionaryClient.MODE_ONLINE_ONLY)
        };
        queryMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        queryMode.setSelection(OnlineDictionaryClient.mode(this));
        queryMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == OnlineDictionaryClient.MODE_ONLINE_ONLY && !onlineEnabled.isChecked()) {
                    onlineEnabled.setChecked(true);
                }
                OnlineDictionaryClient.setMode(DictionarySettingsActivity.this, position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(queryMode, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tests = new LinearLayout(this);
        tests.setOrientation(LinearLayout.HORIZONTAL);
        Button testEnglish = new Button(this);
        testEnglish.setText("测试 English");
        Button testChinese = new Button(this);
        testChinese.setText("测试 中文");
        tests.addView(testEnglish, new LinearLayout.LayoutParams(0, -2, 1f));
        tests.addView(testChinese, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(tests);
        testEnglish.setOnClickListener(v -> openTest("beautiful"));
        testChinese.setOnClickListener(v -> openTest("美丽"));

        return scroll;
    }

    private void refreshState() {
        if (libraryStatus == null) return;
        int count = DictionaryLibraryManager.installedCount(this);
        libraryStatus.setText("通用本地词典：已安装 " + count + " / " + DictionaryLibraryManager.catalog().size() + " 个");

        boolean legacy = DictionaryManager.isReady(this);
        if (legacy) {
            double mb = DictionaryManager.installedBytes(this) / 1024d / 1024d;
            legacyStatus.setText(String.format(Locale.ROOT,
                    "检测到旧版 ECCEDICT 数据 · %.1f MB。查询会暂时兼容它；安装新的通用词库后可以删除。", mb));
            legacyDelete.setVisibility(View.VISIBLE);
        } else {
            legacyStatus.setText("旧版专用 ECCEDICT 数据：未安装（已不再需要）。");
            legacyDelete.setVisibility(View.GONE);
        }
        if (onlineEnabled != null) onlineEnabled.setChecked(OnlineDictionaryClient.isEnabled(this));
        if (queryMode != null) queryMode.setSelection(OnlineDictionaryClient.mode(this));
    }

    private void confirmDeleteLegacy() {
        if (!DictionaryManager.isReady(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("删除旧版 ECCEDICT 数据？")
                .setMessage("新的词库中心和在线 Wiktionary 不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, which) -> {
                    DictionaryManager.delete(this);
                    refreshState();
                })
                .show();
    }

    private void openTest(String text) {
        startActivity(new Intent(this, DictionaryActivity.class)
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true));
    }

    private void sectionTitle(LinearLayout root, String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(18), 0, 0);
        root.addView(title);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
