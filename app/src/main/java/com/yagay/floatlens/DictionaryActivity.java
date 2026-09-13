package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local ECDICT lookup surface used by FloatLens' text selection menu and ACTION_PROCESS_TEXT. */
public final class DictionaryActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText queryEdit;
    private TextView dictionaryStatus;
    private TextView resultView;
    private ProgressBar progress;
    private Button downloadButton;
    private Button deleteButton;
    private Button lookupButton;
    private String pendingQuery = "";
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("英汉词典");
        setContentView(buildUi());
        applyIntent(getIntent(), true);
        refreshDictionaryState();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent, true);
        refreshDictionaryState();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("FloatLens 英汉词典");
        title.setTextSize(24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("本地 ECDICT · 词典数据独立下载，不打包进 APK");
        subtitle.setTextSize(14);
        subtitle.setAlpha(0.72f);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        queryEdit = new EditText(this);
        queryEdit.setSingleLine(true);
        queryEdit.setHint("输入英文单词或短语");
        queryEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        queryEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        lookupButton = new Button(this);
        lookupButton.setText("查询");
        searchRow.addView(queryEdit, new LinearLayout.LayoutParams(0, -2, 1f));
        searchRow.addView(lookupButton, new LinearLayout.LayoutParams(dp(88), -2));
        root.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));
        lookupButton.setOnClickListener(v -> lookupCurrent());
        queryEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                lookupCurrent();
                return true;
            }
            return false;
        });

        dictionaryStatus = new TextView(this);
        dictionaryStatus.setPadding(0, dp(12), 0, dp(8));
        root.addView(dictionaryStatus);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));

        LinearLayout management = new LinearLayout(this);
        management.setOrientation(LinearLayout.HORIZONTAL);
        downloadButton = new Button(this);
        deleteButton = new Button(this);
        downloadButton.setText("下载词典");
        deleteButton.setText("删除本地词典");
        management.addView(downloadButton, new LinearLayout.LayoutParams(0, -2, 1f));
        management.addView(deleteButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(management);
        downloadButton.setOnClickListener(v -> startDownload());
        deleteButton.setOnClickListener(v -> {
            if (DictionaryManager.isDownloading()) {
                Toast.makeText(this, "词典正在下载或构建，暂时不能删除", Toast.LENGTH_SHORT).show();
                return;
            }
            DictionaryManager.delete(this);
            resultView.setText("");
            refreshDictionaryState();
            Toast.makeText(this, "本地词典已删除", Toast.LENGTH_SHORT).show();
        });

        TextView divider = new TextView(this);
        divider.setText("查询结果");
        divider.setTextSize(18);
        divider.setTypeface(divider.getTypeface(), android.graphics.Typeface.BOLD);
        divider.setPadding(0, dp(22), 0, dp(8));
        root.addView(divider);

        resultView = new TextView(this);
        resultView.setTextSize(16);
        resultView.setTextIsSelectable(true);
        resultView.setLineSpacing(0f, 1.16f);
        resultView.setPadding(dp(2), dp(4), dp(2), dp(12));
        root.addView(resultView, new LinearLayout.LayoutParams(-1, -2));

        Button copy = new Button(this);
        copy.setText("复制查询结果");
        copy.setOnClickListener(v -> {
            String text = resultView.getText() == null ? "" : resultView.getText().toString().trim();
            if (text.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens Dictionary", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy, new LinearLayout.LayoutParams(-1, -2));

        TextView source = new TextView(this);
        source.setText("数据源：ECDICT（本地离线查询）\n下载源：skywind3000/ECDICT 的 ecdict.csv");
        source.setTextSize(12);
        source.setAlpha(0.6f);
        source.setPadding(0, dp(18), 0, 0);
        root.addView(source);

        return scroll;
    }

    private void applyIntent(Intent intent, boolean autoLookup) {
        String incoming = textFromIntent(intent);
        if (!incoming.isBlank()) {
            pendingQuery = DictionaryManager.normalizeQuery(incoming);
            queryEdit.setText(pendingQuery);
            queryEdit.setSelection(queryEdit.length());
            if (autoLookup && DictionaryManager.isReady(this)) lookup(pendingQuery);
        }
    }

    private static String textFromIntent(Intent intent) {
        if (intent == null) return "";
        CharSequence process = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (process != null && !process.toString().isBlank()) return process.toString();
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        return text == null ? "" : text.toString();
    }

    private void lookupCurrent() {
        String query = DictionaryManager.normalizeQuery(queryEdit.getText().toString());
        if (query.isBlank()) {
            Toast.makeText(this, "请输入英文单词或短语", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingQuery = query;
        if (!DictionaryManager.isReady(this)) {
            resultView.setText("本地英汉词典尚未下载。\n\n点击上方“下载词典”后即可离线查词。");
            return;
        }
        lookup(query);
    }

    private void lookup(String query) {
        if (query == null || query.isBlank()) return;
        lookupButton.setEnabled(false);
        resultView.setText("查询中…");
        executor.execute(() -> {
            try {
                DictionaryManager.Entry entry = DictionaryManager.lookup(this, query);
                String formatted = entry == null
                        ? "未找到 “" + query + "”\n\n可以尝试选择更完整的英文单词或短语。"
                        : formatEntry(entry);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    resultView.setText(formatted);
                    lookupButton.setEnabled(true);
                });
            } catch (Throwable t) {
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    resultView.setText("查询失败：" + message);
                    lookupButton.setEnabled(true);
                });
            }
        });
    }

    private String formatEntry(DictionaryManager.Entry e) {
        StringBuilder out = new StringBuilder();
        out.append(e.word);
        if (!e.phonetic.isBlank()) out.append("\n/").append(e.phonetic).append('/');
        if (!e.translation.isBlank()) {
            out.append("\n\n中文释义\n").append(e.translation.trim());
        }
        if (!e.definition.isBlank()) {
            out.append("\n\n英文释义\n").append(e.definition.trim());
        }
        if (!e.pos.isBlank()) out.append("\n\n词性：").append(e.pos);
        if (!e.tag.isBlank()) out.append("\n标签：").append(e.tag);
        if (e.collins > 0) out.append("\n柯林斯：").append(e.collins).append(" 星");
        if (e.oxford) out.append("\nOxford 3000：是");
        if (e.bnc > 0) out.append("\nBNC 词频：").append(e.bnc);
        if (e.frq > 0) out.append("\n当代词频：").append(e.frq);
        if (!e.exchange.isBlank()) out.append("\n词形变化：").append(e.exchange);
        return out.toString();
    }

    private void startDownload() {
        if (DictionaryManager.isDownloading()) {
            Toast.makeText(this, "词典正在下载或构建", Toast.LENGTH_SHORT).show();
            return;
        }
        downloadButton.setEnabled(false);
        deleteButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        DictionaryManager.download(this, new DictionaryManager.Callback() {
            @Override public void onProgress(String stage, int percent) {
                if (destroyed) return;
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(percent);
                dictionaryStatus.setText("ECDICT：" + stage + " " + percent + "%");
            }

            @Override public void onSuccess() {
                if (destroyed) return;
                Toast.makeText(DictionaryActivity.this, "英汉词典已安装，可离线使用", Toast.LENGTH_SHORT).show();
                refreshDictionaryState();
                if (!pendingQuery.isBlank()) lookup(pendingQuery);
            }

            @Override public void onFailure(String message) {
                if (destroyed) return;
                Toast.makeText(DictionaryActivity.this, "词典下载失败：" + message, Toast.LENGTH_LONG).show();
                refreshDictionaryState();
            }
        });
    }

    private void refreshDictionaryState() {
        boolean ready = DictionaryManager.isReady(this);
        boolean busy = DictionaryManager.isDownloading();
        long bytes = DictionaryManager.installedBytes(this);
        if (busy) {
            dictionaryStatus.setText("ECDICT：正在下载或建立本地索引…");
            progress.setVisibility(View.VISIBLE);
        } else if (ready) {
            dictionaryStatus.setText(String.format(Locale.ROOT,
                    "ECDICT：已下载 · %.1f MB · 可离线查询", bytes / 1024d / 1024d));
            progress.setVisibility(View.GONE);
        } else {
            dictionaryStatus.setText("ECDICT：未下载 · 源 CSV 约 66 MB，安装后本地数据库占用会更大");
            progress.setVisibility(View.GONE);
        }
        downloadButton.setText(ready ? "下载 / 更新词典" : "下载词典");
        downloadButton.setEnabled(!busy);
        deleteButton.setEnabled(ready && !busy);
        lookupButton.setEnabled(!busy);
        if (!ready && !pendingQuery.isBlank() && resultView.getText().toString().isBlank()) {
            resultView.setText("已选中：" + pendingQuery + "\n\n首次使用请先下载本地 ECDICT 词典。下载完成后会自动查询。");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
