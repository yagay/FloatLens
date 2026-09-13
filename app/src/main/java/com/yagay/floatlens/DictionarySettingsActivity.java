package com.yagay.floatlens;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** Dedicated settings page for the separately downloaded ECCEDICT data. */
public final class DictionarySettingsActivity extends AppCompatActivity {
    private TextView statusView;
    private ProgressBar progress;
    private Button downloadButton;
    private Button deleteButton;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("本地词典");
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

        TextView title = new TextView(this);
        title.setText("ECCEDICT 中英 / 英中词典");
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("词典文件独立下载，不打包进 FloatLens APK。下载完成后会在本机建立 English → 中文和中文 → English 双向索引，以后可完全离线查询。");
        note.setTextSize(14);
        note.setAlpha(0.76f);
        note.setPadding(0, dp(8), 0, dp(16));
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

        Button test = new Button(this);
        test.setText("测试词典弹窗");
        test.setOnClickListener(v -> {
            if (!DictionaryManager.isReady(this)) {
                Toast.makeText(this, "请先下载 ECCEDICT", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, DictionaryActivity.class)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, "beautiful")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true));
        });
        root.addView(test, new LinearLayout.LayoutParams(-1, -2));

        TextView details = new TextView(this);
        details.setText("数据源：H1DDENADM1N/ECCEDICT\n基础 CSV 当前约 66 MB；建立中文反向索引后，本地数据库会明显大于下载文件。\n更新词典时，旧数据库会保留到新数据库构建和校验完成后才替换。");
        details.setTextSize(13);
        details.setAlpha(0.68f);
        details.setPadding(0, dp(20), 0, 0);
        root.addView(details);

        return scroll;
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
                .setMessage("只会删除单独下载的 ECCEDICT 数据，不会影响 FloatLens APK。")
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
            statusView.setText("ECCEDICT：未下载");
            progress.setVisibility(View.GONE);
        }
        downloadButton.setText(ready ? "下载 / 更新 ECCEDICT" : "下载 ECCEDICT");
        downloadButton.setEnabled(!busy);
        deleteButton.setEnabled(ready && !busy);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
