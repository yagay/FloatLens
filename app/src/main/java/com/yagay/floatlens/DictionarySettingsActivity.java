package com.yagay.floatlens;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** Dictionary settings for the optional local ECCEDICT database and online Wiktionary lookup. */
public final class DictionarySettingsActivity extends AppCompatActivity {
    private TextView statusView;
    private ProgressBar progress;
    private Button downloadButton;
    private Button deleteButton;
    private CheckBox onlineEnabled;
    private Spinner queryMode;
    private volatile boolean destroyed;

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

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        sectionTitle(root, "本地词典 · ECCEDICT");

        TextView note = new TextView(this);
        note.setText("词典文件独立下载，不打包进 FloatLens APK。下载完成后会在本机建立 English → 中文和中文 → English 双向索引，可完全离线查询。");
        note.setTextSize(14);
        note.setAlpha(0.76f);
        note.setPadding(0, dp(6), 0, dp(14));
        root.addView(note);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 0, 0, dp(8));
        root.addView(statusView);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(10)));

        downloadButton = new Button(this);
        downloadButton.setText("下载 ECCEDICT");
        downloadButton.setOnClickListener(v -> startDownload());
        root.addView(downloadButton, new LinearLayout.LayoutParams(-1, -2));

        deleteButton = new Button(this);
        deleteButton.setText("删除本地词典");
        deleteButton.setOnClickListener(v -> confirmDelete());
        root.addView(deleteButton, new LinearLayout.LayoutParams(-1, -2));

        TextView localDetails = new TextView(this);
        localDetails.setText("数据源：H1DDENADM1N/ECCEDICT\n基础 CSV 当前约 66 MB；建立中文反向索引后，本地数据库会明显大于下载文件。更新时旧库会保留到新库校验完成后才替换。");
        localDetails.setTextSize(13);
        localDetails.setAlpha(0.65f);
        localDetails.setPadding(0, dp(10), 0, 0);
        root.addView(localDetails);

        sectionTitle(root, "在线词典 · Wiktionary");

        TextView onlineNote = new TextView(this);
        onlineNote.setText("无需下载、无需 API Key。英文查询使用中文 Wiktionary 返回中文释义；中文查询使用英文 Wiktionary 返回英文释义。查询内容会发送到 Wikimedia 服务器。");
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
        queryMode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modes));
        queryMode.setSelection(OnlineDictionaryClient.mode(this));
        queryMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                if (position == OnlineDictionaryClient.MODE_ONLINE_ONLY
                        && !onlineEnabled.isChecked()) {
                    onlineEnabled.setChecked(true);
                }
                OnlineDictionaryClient.setMode(DictionarySettingsActivity.this, position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(queryMode, new LinearLayout.LayoutParams(-1, -2));

        TextView onlineSource = new TextView(this);
        onlineSource.setText("来源：Wiktionary / Wikimedia · 在线数据不保存为本地词典文件");
        onlineSource.setTextSize(13);
        onlineSource.setAlpha(0.65f);
        onlineSource.setPadding(0, dp(10), 0, dp(8));
        root.addView(onlineSource);

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

    private void openTest(String text) {
        startActivity(new Intent(this, DictionaryActivity.class)
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true));
    }

    private void startDownload() {
        if (DictionaryManager.isDownloading()) {
            Toast.makeText(this, "词典正在下载或建立索引", Toast.LENGTH_SHORT).show();
            return;
        }
        downloadButton.setEnabled(false);
        deleteButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        statusView.setText("准备下载 ECCEDICT…");

        DictionaryManager.download(this, new DictionaryManager.Callback() {
            @Override public void onProgress(String stage, int percent) {
                if (destroyed) return;
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(percent);
                statusView.setText(stage + " · " + percent + "%");
            }

            @Override public void onSuccess() {
                if (destroyed) return;
                Toast.makeText(DictionarySettingsActivity.this,
                        "ECCEDICT 已安装，中英文都可以离线查询", Toast.LENGTH_SHORT).show();
                refreshState();
            }

            @Override public void onFailure(String message) {
                if (destroyed) return;
                Toast.makeText(DictionarySettingsActivity.this,
                        "词典下载/构建失败：" + message, Toast.LENGTH_LONG).show();
                refreshState();
            }
        });
    }

    private void confirmDelete() {
        if (DictionaryManager.isDownloading()) {
            Toast.makeText(this, "词典正在下载或建立索引，暂时不能删除", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!DictionaryManager.isReady(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("删除本地词典？")
                .setMessage("只会删除单独下载的 ECCEDICT 数据；在线 Wiktionary 仍然可以使用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, which) -> {
                    DictionaryManager.delete(this);
                    refreshState();
                    Toast.makeText(this, "本地词典已删除", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshState() {
        if (statusView == null) return;
        boolean ready = DictionaryManager.isReady(this);
        boolean busy = DictionaryManager.isDownloading();
        long bytes = DictionaryManager.installedBytes(this);
        if (busy) {
            statusView.setText("ECCEDICT：正在下载或建立中英双向索引…");
            progress.setVisibility(View.VISIBLE);
        } else if (ready) {
            statusView.setText(String.format(Locale.ROOT,
                    "ECCEDICT：已安装 · %.1f MB · 中英/英中离线可用",
                    bytes / 1024d / 1024d));
            progress.setVisibility(View.GONE);
        } else {
            statusView.setText("ECCEDICT：未下载 · 在线词典不受影响");
            progress.setVisibility(View.GONE);
        }
        downloadButton.setText(ready ? "下载 / 更新 ECCEDICT" : "下载 ECCEDICT");
        downloadButton.setEnabled(!busy);
        deleteButton.setEnabled(ready && !busy);
        if (onlineEnabled != null) onlineEnabled.setChecked(OnlineDictionaryClient.isEnabled(this));
        if (queryMode != null) queryMode.setSelection(OnlineDictionaryClient.mode(this));
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
