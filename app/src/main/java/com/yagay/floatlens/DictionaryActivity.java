package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Html;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Compact popup combining generic local dictionaries, legacy migration fallback and Wiktionary. */
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
        sourceView.setText("本地词库 / Wiktionary");
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
        boolean anyLocal = DictionaryLibraryManager.installedCount(this) > 0 || DictionaryManager.isReady(this);
        boolean online = OnlineDictionaryClient.isEnabled(this);
        int mode = OnlineDictionaryClient.mode(this);
        if ((!anyLocal || mode == OnlineDictionaryClient.MODE_ONLINE_ONLY) && !online) {
            progress.setVisibility(View.GONE);
            directionView.setText(directionLabel(query));
            resultView.setText("没有可用的词典来源。\n\n可以到“词典设置 → 在线词库中心”下载本地词典，或者启用在线 Wiktionary。");
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
        boolean onlineEnabled = OnlineDictionaryClient.isEnabled(this);
        List<DictionaryLibraryManager.Hit> genericHits = new ArrayList<>();
        DictionaryManager.LookupResult legacy = null;
        OnlineDictionaryClient.Result online = null;
        String onlineError = "";

        if (mode != OnlineDictionaryClient.MODE_ONLINE_ONLY) {
            genericHits = DictionaryLibraryManager.lookup(this, value);
            // Migration fallback only: once a new library has an answer, do not duplicate the old ECCEDICT result.
            if (genericHits.isEmpty() && DictionaryManager.isReady(this)) {
                try { legacy = DictionaryManager.lookup(this, value); }
                catch (Throwable t) { DiagnosticLog.i(this, "DICTIONARY", "legacy lookup failed=" + t); }
            }
        }

        boolean localHas = !genericHits.isEmpty() || (legacy != null && !legacy.isEmpty());
        boolean needOnline = onlineEnabled && (mode == OnlineDictionaryClient.MODE_BOTH
                || mode == OnlineDictionaryClient.MODE_ONLINE_ONLY
                || (mode == OnlineDictionaryClient.MODE_LOCAL_FIRST && !localHas));
        if (needOnline) {
            try { online = OnlineDictionaryClient.lookup(value); }
            catch (Throwable t) {
                onlineError = messageOf(t);
                DiagnosticLog.i(this, "DICTIONARY_ONLINE", "lookup failed=" + t);
            }
        }
        boolean onlineHas = online != null && !online.isEmpty();

        StringBuilder out = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        if (!genericHits.isEmpty()) {
            out.append(formatGeneric(genericHits, sources));
        } else if (legacy != null && !legacy.isEmpty()) {
            sources.add("旧版 ECCEDICT");
            out.append(formatLegacy(legacy));
        }
        if (onlineHas) {
            if (out.length() > 0) out.append("\n\n━━━━━━━━━━━━━━━━\n\n");
            out.append("在线 · Wiktionary\n\n").append(formatOnline(online));
            sources.add("Wiktionary 在线");
        }

        if (out.length() == 0) {
            out.append("未找到 “").append(value).append("”");
            if (!onlineError.isBlank()) out.append("\n\n在线查询失败：").append(onlineError);
            else out.append("\n\n可以尝试选择更完整的单词或更简短的中文词语。");
        } else if (!onlineError.isBlank() && mode == OnlineDictionaryClient.MODE_BOTH) {
            out.append("\n\n在线 Wiktionary 暂时不可用：").append(onlineError);
        }

        String source = sources.isEmpty() ? "本地词库 / Wiktionary" : String.join(" · ", sources);
        return new RenderedLookup(out.toString(), source, localHas || onlineHas);
    }

    private String formatGeneric(List<DictionaryLibraryManager.Hit> hits, Set<String> sources) {
        StringBuilder out = new StringBuilder();
        String current = "";
        int number = 0;
        for (DictionaryLibraryManager.Hit hit : hits) {
            if (!hit.dictionaryTitle.equals(current)) {
                if (out.length() > 0) out.append("\n\n━━━━━━━━━━━━━━━━\n\n");
                current = hit.dictionaryTitle;
                sources.add(current);
                number = 0;
                out.append("本地 · ").append(current).append("\n\n");
            }
            number++;
            if (number > 1) out.append("\n\n");
            String display = hit.displayWord.isBlank() ? hit.word : hit.displayWord;
            out.append(number).append(". ").append(display);
            String content = cleanLocalContent(hit.content);
            if (!content.isBlank()) out.append("\n").append(content);
        }
        return out.toString();
    }

    private String cleanLocalContent(String content) {
        if (content == null) return "";
        String value = content.replace('\u0000', ' ').trim();
        if (value.contains("<") && value.contains(">")) {
            try { value = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString(); }
            catch (Throwable ignored) {}
        }
        value = value.replace('\u00A0', ' ').replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\\n\\n").trim();
        return value.length() > 2200 ? value.substring(0, 2200).trim() + "…" : value;
    }

    private String formatLegacy(DictionaryManager.LookupResult result) {
        if (!result.chineseQuery) return formatLegacyEnglish(result.entries.get(0));
        StringBuilder out = new StringBuilder("本地 · 旧版 ECCEDICT\n\n");
        int count = 0;
        for (DictionaryManager.Entry e : result.entries) {
            if (count > 0) out.append("\n\n");
            count++;
            out.append(count).append(". ").append(e.word);
            if (!e.phonetic.isBlank()) out.append("  /").append(e.phonetic).append('/');
            if (!e.translation.isBlank()) out.append("\n").append(compact(e.translation));
            if (!e.pos.isBlank()) out.append("\n词性：").append(e.pos);
        }
        return out.toString();
    }

    private String formatLegacyEnglish(DictionaryManager.Entry e) {
        StringBuilder out = new StringBuilder("本地 · 旧版 ECCEDICT\n\n");
        out.append(e.word);
        if (!e.phonetic.isBlank()) out.append("\n/").append(e.phonetic).append('/');
        if (!e.translation.isBlank()) out.append("\n\n中文释义\n").append(e.translation.trim());
        if (!e.definition.isBlank()) out.append("\n\n英文释义\n").append(e.definition.trim());
        if (!e.pos.isBlank()) out.append("\n\n词性：").append(e.pos);
        return out.toString();
    }

    private String formatOnline(OnlineDictionaryClient.Result result) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (OnlineDictionaryClient.Section section : result.sections) {
            if (out.length() > 0) out.append("\n\n");
            String heading = section.partOfSpeech.isBlank() ? section.language : section.partOfSpeech;
            if (!heading.isBlank()) out.append("【").append(heading).append("】\n");
            for (OnlineDictionaryClient.Definition def : section.definitions) {
                n++;
                out.append(n).append(". ").append(def.text);
                if (!def.example.isBlank()) out.append("\n   例：").append(def.example);
                out.append('\n');
            }
        }
        return out.toString().trim();
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
