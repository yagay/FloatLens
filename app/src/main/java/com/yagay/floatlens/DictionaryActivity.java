package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lookup-only popup. Dictionary download/update lives in the Settings dictionary page. */
public final class DictionaryActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView queryView;
    private TextView directionView;
    private TextView resultView;
    private ProgressBar progress;
    private Button copyButton;
    private String query = "";
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setFinishOnTouchOutside(true);
        setContentView(buildUi());
        applyIntent(getIntent());
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenWidth - dp(28), dp(560));
        window.setLayout(Math.max(dp(280), width), WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(resolveBackgroundColor());
        bg.setCornerRadius(dp(20));
        root.setBackground(bg);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("中英 · 英中词典");
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        queryView = new TextView(this);
        queryView.setTextSize(18);
        queryView.setTypeface(queryView.getTypeface(), android.graphics.Typeface.BOLD);
        queryView.setPadding(0, dp(12), 0, dp(2));
        root.addView(queryView);

        directionView = new TextView(this);
        directionView.setTextSize(13);
        directionView.setAlpha(0.68f);
        directionView.setPadding(0, 0, 0, dp(8));
        root.addView(directionView);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(36)));

        resultView = new TextView(this);
        resultView.setTextSize(16);
        resultView.setTextIsSelectable(true);
        resultView.setLineSpacing(0f, 1.16f);
        resultView.setPadding(0, dp(4), 0, dp(10));
        root.addView(resultView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        copyButton = new Button(this);
        copyButton.setText("复制");
        Button settings = new Button(this);
        settings.setText("词典设置");
        Button close = new Button(this);
        close.setText("关闭");
        buttons.addView(copyButton, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(settings, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(buttons);

        TextView source = new TextView(this);
        source.setText("ECCEDICT · 本地离线查询");
        source.setTextSize(11);
        source.setAlpha(0.52f);
        source.setGravity(Gravity.CENTER_HORIZONTAL);
        source.setPadding(0, dp(8), 0, 0);
        root.addView(source);

        copyButton.setOnClickListener(v -> copyResult());
        settings.setOnClickListener(v -> {
            startActivity(new Intent(this, DictionarySettingsActivity.class));
            finish();
        });
        close.setOnClickListener(v -> finish());
        return scroll;
    }

    private void applyIntent(Intent intent) {
        query = DictionaryManager.normalizeQuery(textFromIntent(intent));
        queryView.setText(query.isBlank() ? "没有可查询的文字" : query);
        resultView.setText("");
        copyButton.setEnabled(false);

        if (query.isBlank()) {
            progress.setVisibility(View.GONE);
            directionView.setText("请选择中文或英文后再打开词典");
            resultView.setText("未收到可查询的文字。");
            return;
        }

        if (!DictionaryManager.isReady(this)) {
            progress.setVisibility(View.GONE);
            directionView.setText("ECCEDICT 尚未下载");
            resultView.setText("本地中英词典尚未安装。\n\n请到 FloatLens 设置 → 本地词典 → ECCEDICT 下载词典数据。下载完成后即可离线直接查询。");
            return;
        }
        lookup(query);
    }

    private void lookup(String value) {
        progress.setVisibility(View.VISIBLE);
        directionView.setText("正在查询…");
        resultView.setText("");
        executor.execute(() -> {
            try {
                DictionaryManager.LookupResult result = DictionaryManager.lookup(this, value);
                String formatted = formatResult(result);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    progress.setVisibility(View.GONE);
                    directionView.setText(result.chineseQuery ? "中文 → English" : "English → 中文");
                    resultView.setText(formatted);
                    copyButton.setEnabled(!formatted.isBlank());
                });
            } catch (Throwable t) {
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    progress.setVisibility(View.GONE);
                    directionView.setText("查询失败");
                    resultView.setText(message);
                    copyButton.setEnabled(false);
                });
            }
        });
    }

    private String formatResult(DictionaryManager.LookupResult result) {
        if (result == null || result.isEmpty()) {
            return "未找到 “" + query + "”\n\n可以尝试选择更完整的单词或更简短的中文词语。";
        }
        if (!result.chineseQuery) return formatEnglishEntry(result.entries.get(0));

        StringBuilder out = new StringBuilder();
        int count = 0;
        for (DictionaryManager.Entry e : result.entries) {
            if (count > 0) out.append("\n\n");
            count++;
            out.append(count).append(". ").append(e.word);
            if (!e.phonetic.isBlank()) out.append("  /").append(e.phonetic).append('/');
            if (!e.translation.isBlank()) out.append("\n").append(compact(e.translation));
            if (!e.pos.isBlank()) out.append("\n词性：").append(e.pos);
            if (e.collins > 0) out.append(" · 柯林斯 ").append(e.collins).append("★");
        }
        return out.toString();
    }

    private String formatEnglishEntry(DictionaryManager.Entry e) {
        StringBuilder out = new StringBuilder();
        out.append(e.word);
        if (!e.phonetic.isBlank()) out.append("\n/").append(e.phonetic).append('/');
        if (!e.translation.isBlank()) out.append("\n\n中文释义\n").append(e.translation.trim());
        if (!e.definition.isBlank()) out.append("\n\n英文释义\n").append(e.definition.trim());
        if (!e.pos.isBlank()) out.append("\n\n词性：").append(e.pos);
        if (!e.tag.isBlank()) out.append("\n标签：").append(e.tag);
        if (e.collins > 0) out.append("\n柯林斯：").append(e.collins).append(" 星");
        if (e.oxford) out.append("\nOxford 3000：是");
        if (e.bnc > 0) out.append("\nBNC 词频：").append(e.bnc);
        if (e.frq > 0) out.append("\n当代词频：").append(e.frq);
        if (!e.exchange.isBlank()) out.append("\n词形变化：").append(e.exchange);
        return out.toString();
    }

    private String compact(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s*\\n\\s*", "；");
        return text.length() > 220 ? text.substring(0, 220).trim() + "…" : text;
    }

    private void copyResult() {
        String text = resultView.getText() == null ? "" : resultView.getText().toString().trim();
        if (text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens Dictionary", text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static String textFromIntent(Intent intent) {
        if (intent == null) return "";
        CharSequence process = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (process != null && !process.toString().isBlank()) return process.toString();
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        return text == null ? "" : text.toString();
    }

    private int resolveBackgroundColor() {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
            if (value.resourceId != 0) return getColor(value.resourceId);
            return value.data;
        }
        return 0xFFFFFFFF;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
