package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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

/** Compact dictionary popup combining optional local ECCEDICT and online Wiktionary. */
public final class DictionaryActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView queryView;
    private TextView directionView;
    private TextView resultView;
    private TextView sourceView;
    private ProgressBar progress;
    private Button copyButton;
    private String query = "";
    private volatile boolean destroyed;

    private static final class RenderedLookup {
        final String text;
        final String source;
        final boolean hasContent;

        RenderedLookup(String text, String source, boolean hasContent) {
            this.text = text == null ? "" : text;
            this.source = source == null ? "" : source;
            this.hasContent = hasContent;
        }
    }

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
        queryView.setTextSize(20);
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

        sourceView = new TextView(this);
        sourceView.setText("ECCEDICT / Wiktionary");
        sourceView.setTextSize(11);
        sourceView.setAlpha(0.52f);
        sourceView.setGravity(Gravity.CENTER_HORIZONTAL);
        sourceView.setPadding(0, dp(8), 0, 0);
        root.addView(sourceView);

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
            sourceView.setText("");
            return;
        }

        boolean localReady = DictionaryManager.isReady(this);
        boolean onlineEnabled = OnlineDictionaryClient.isEnabled(this);
        int mode = OnlineDictionaryClient.mode(this);
        boolean canLocal = localReady && mode != OnlineDictionaryClient.MODE_ONLINE_ONLY;
        boolean canOnline = onlineEnabled;
        if (!canLocal && !canOnline) {
            progress.setVisibility(View.GONE);
            directionView.setText(directionLabel(query));
            resultView.setText("没有可用的词典来源。\n\n可以到“词典设置”启用在线 Wiktionary，或者下载 ECCEDICT 本地词典。");
            sourceView.setText("未启用词典来源");
            return;
        }
        lookup(query);
    }

    private void lookup(String value) {
        progress.setVisibility(View.VISIBLE);
        directionView.setText(directionLabel(value) + " · 正在查询…");
        resultView.setText("");
        sourceView.setText("查询中…");
        executor.execute(() -> {
            RenderedLookup rendered = performLookup(value);
            runOnUiThread(() -> {
                if (destroyed) return;
                progress.setVisibility(View.GONE);
                directionView.setText(directionLabel(value));
                resultView.setText(rendered.text);
                sourceView.setText(rendered.source);
                copyButton.setEnabled(rendered.hasContent);
            });
        });
    }

    private RenderedLookup performLookup(String value) {
        int mode = OnlineDictionaryClient.mode(this);
        boolean localReady = DictionaryManager.isReady(this);
        boolean onlineEnabled = OnlineDictionaryClient.isEnabled(this);
        DictionaryManager.LookupResult local = null;
        OnlineDictionaryClient.Result online = null;
        String localError = "";
        String onlineError = "";

        if (mode != OnlineDictionaryClient.MODE_ONLINE_ONLY && localReady) {
            try {
                local = DictionaryManager.lookup(this, value);
            } catch (Throwable t) {
                localError = messageOf(t);
                DiagnosticLog.i(this, "DICTIONARY", "local lookup failed=" + t);
            }
        }

        boolean localHasResult = local != null && !local.isEmpty();
        boolean needOnline = onlineEnabled && (mode == OnlineDictionaryClient.MODE_BOTH
                || mode == OnlineDictionaryClient.MODE_ONLINE_ONLY
                || (mode == OnlineDictionaryClient.MODE_LOCAL_FIRST && !localHasResult));
        if (needOnline) {
            try {
                online = OnlineDictionaryClient.lookup(value);
            } catch (Throwable t) {
                onlineError = messageOf(t);
                DiagnosticLog.i(this, "DICTIONARY_ONLINE", "lookup failed=" + t);
            }
        }

        boolean onlineHasResult = online != null && !online.isEmpty();
        StringBuilder out = new StringBuilder();

        if (localHasResult) {
            if (onlineHasResult) out.append("本地 · ECCEDICT\n\n");
            out.append(formatLocal(local));
        }

        if (onlineHasResult) {
            if (out.length() > 0) out.append("\n\n━━━━━━━━━━━━━━━━\n\n");
            if (localHasResult) out.append("在线 · Wiktionary\n\n");
            out.append(formatOnline(online));
        }

        if (out.length() == 0) {
            out.append("未找到 “").append(value).append("”");
            if (!onlineError.isBlank()) {
                out.append("\n\n在线查询失败：").append(onlineError);
            } else if (!localError.isBlank()) {
                out.append("\n\n本地查询失败：").append(localError);
            } else if (!localReady && !needOnline) {
                out.append("\n\nECCEDICT 尚未下载。");
            } else {
                out.append("\n\n可以尝试选择更完整的单词或更简短的中文词语。");
            }
        } else if (!onlineError.isBlank() && mode == OnlineDictionaryClient.MODE_BOTH) {
            out.append("\n\n在线 Wiktionary 暂时不可用：").append(onlineError);
        }

        String source;
        if (localHasResult && onlineHasResult) source = "ECCEDICT · 本地  +  Wiktionary · 在线";
        else if (localHasResult) source = "ECCEDICT · 本地离线";
        else if (onlineHasResult) source = "Wiktionary · Wikimedia 在线";
        else if (needOnline && !onlineError.isBlank()) source = "Wiktionary · 在线查询失败";
        else source = localReady ? "ECCEDICT / Wiktionary" : "Wiktionary / ECCEDICT 未安装";

        return new RenderedLookup(out.toString(), source, localHasResult || onlineHasResult);
    }

    private String formatLocal(DictionaryManager.LookupResult result) {
        if (result == null || result.isEmpty()) return "";
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

    private String formatOnline(OnlineDictionaryClient.Result result) {
        if (result == null || result.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int definitionNumber = 0;
        for (OnlineDictionaryClient.Section section : result.sections) {
            if (out.length() > 0) out.append("\n\n");
            String heading = section.partOfSpeech;
            if (heading.isBlank()) heading = section.language;
            if (!heading.isBlank()) out.append("【").append(heading).append("】\n");
            for (OnlineDictionaryClient.Definition def : section.definitions) {
                definitionNumber++;
                out.append(definitionNumber).append(". ").append(def.text);
                if (!def.example.isBlank()) out.append("\n   例：").append(def.example);
                if (def != section.definitions.get(section.definitions.size() - 1)) out.append('\n');
            }
        }
        return out.toString().trim();
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

    private String directionLabel(String value) {
        return OnlineDictionaryClient.isChineseQuery(value) ? "中文 → English" : "English → 中文";
    }

    private String compact(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s*\\n\\s*", "；");
        return text.length() > 220 ? text.substring(0, 220).trim() + "…" : text;
    }

    private static String messageOf(Throwable t) {
        if (t == null) return "未知错误";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
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
