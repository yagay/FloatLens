package com.yagay.floatlens;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AI provider settings for OpenRouter, Gemini, Groq and generic OpenAI-compatible endpoints. */
public final class AiSettingsActivity extends AppCompatActivity {
    private static final String[] PROVIDERS = {
            AiConfigStore.PROVIDER_OPENROUTER,
            AiConfigStore.PROVIDER_GEMINI,
            AiConfigStore.PROVIDER_GROQ,
            AiConfigStore.PROVIDER_CUSTOM
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Spinner providerSpinner;
    private EditText baseUrlEdit;
    private EditText modelEdit;
    private EditText apiKeyEdit;
    private EditText systemPromptEdit;
    private TextView providerNoteView;
    private TextView keyStateView;
    private TextView statusView;
    private ProgressBar progress;
    private Button testButton;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("AI 助手设置");
        setContentView(buildUi());
        loadState();
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
        root.setPadding(dp(20), dp(20), dp(20), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("FloatLens AI 助手");
        title.setTextSize(24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("支持 OpenRouter Free、Gemini Free Tier、Groq Free Tier 和任意 OpenAI Compatible 接口。选中文字、OCR 结果或圈选文字后可以直接发送给 AI。每个提供商的 API Key 都会单独使用 Android Keystore 加密保存。");
        note.setTextSize(14);
        note.setAlpha(0.76f);
        note.setPadding(0, dp(8), 0, dp(18));
        root.addView(note);

        label(root, "AI Provider");
        providerSpinner = new Spinner(this);
        providerSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        "OpenRouter · Free Models Router",
                        "Gemini · Free Tier",
                        "Groq · Free Tier",
                        "自定义 OpenAI Compatible"
                }));
        root.addView(providerSpinner);

        providerNoteView = new TextView(this);
        providerNoteView.setTextSize(13);
        providerNoteView.setAlpha(0.7f);
        providerNoteView.setPadding(0, dp(5), 0, dp(10));
        root.addView(providerNoteView);

        label(root, "Base URL");
        baseUrlEdit = new EditText(this);
        baseUrlEdit.setSingleLine(true);
        baseUrlEdit.setHint("https://example.com/v1");
        root.addView(baseUrlEdit, new LinearLayout.LayoutParams(-1, -2));

        label(root, "Model");
        modelEdit = new EditText(this);
        modelEdit.setSingleLine(true);
        modelEdit.setHint("模型 ID");
        root.addView(modelEdit, new LinearLayout.LayoutParams(-1, -2));

        label(root, "API Key");
        apiKeyEdit = new EditText(this);
        apiKeyEdit.setSingleLine(true);
        apiKeyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyEdit.setHint("留空表示保留当前 Provider 已保存的 Key");
        root.addView(apiKeyEdit, new LinearLayout.LayoutParams(-1, -2));

        keyStateView = new TextView(this);
        keyStateView.setTextSize(13);
        keyStateView.setAlpha(0.65f);
        keyStateView.setPadding(0, dp(2), 0, dp(10));
        root.addView(keyStateView);

        Button clearKey = new Button(this);
        clearKey.setText("清除当前 Provider 的 API Key");
        clearKey.setOnClickListener(v -> {
            String provider = selectedProvider();
            AiConfigStore.clearApiKey(this, provider);
            apiKeyEdit.setText("");
            refreshKeyState(provider);
            updateStatus(provider);
            Toast.makeText(this, AiConfigStore.providerLabel(provider) + " 的 API Key 已清除", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearKey, new LinearLayout.LayoutParams(-1, -2));

        label(root, "系统提示词");
        systemPromptEdit = new EditText(this);
        systemPromptEdit.setMinLines(3);
        systemPromptEdit.setGravity(android.view.Gravity.TOP);
        systemPromptEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(systemPromptEdit, new LinearLayout.LayoutParams(-1, -2));

        Button save = new Button(this);
        save.setText("保存设置");
        save.setOnClickListener(v -> saveState(true));
        root.addView(save, new LinearLayout.LayoutParams(-1, -2));

        testButton = new Button(this);
        testButton.setText("测试连接");
        testButton.setOnClickListener(v -> testConnection());
        root.addView(testButton, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(36)));

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(8), 0, 0);
        root.addView(statusView);

        TextView privacy = new TextView(this);
        privacy.setText("隐私提示：使用在线 AI 时，你发送的文字/OCR 内容会传给所选服务商。Gemini Free Tier 的内容可能被 Google 用于改进产品。自定义接口可填写你自己的服务器地址；HTTP 地址仅建议用于你信任的局域网服务。");
        privacy.setTextSize(13);
        privacy.setAlpha(0.68f);
        privacy.setPadding(0, dp(22), 0, 0);
        root.addView(privacy);

        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                String provider = selectedProvider();
                updateProviderUi(provider);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        return scroll;
    }

    private void loadState() {
        String provider = AiConfigStore.provider(this);
        providerSpinner.setSelection(providerIndex(provider));
        systemPromptEdit.setText(AiConfigStore.systemPrompt(this));
        updateProviderUi(provider);
    }

    private void updateProviderUi(String provider) {
        if (baseUrlEdit == null || modelEdit == null) return;
        boolean custom = AiConfigStore.PROVIDER_CUSTOM.equals(provider);
        baseUrlEdit.setEnabled(custom);
        baseUrlEdit.setText(AiConfigStore.baseUrl(this, provider));
        modelEdit.setText(AiConfigStore.model(this, provider));
        apiKeyEdit.setText("");
        refreshKeyState(provider);
        providerNoteView.setText(providerNote(provider));
        updateStatus(provider);
    }

    private String providerNote(String provider) {
        return switch (provider) {
            case AiConfigStore.PROVIDER_GEMINI ->
                    "Google 官方 OpenAI-compatible 接口。默认模型：gemini-3.8-flash；当前提供 Free Tier，但有速率限制。";
            case AiConfigStore.PROVIDER_GROQ ->
                    "Groq 官方 OpenAI-compatible 接口。默认模型：qwen/qwen3.8-27b；Free Plan 有请求/Token 限额。";
            case AiConfigStore.PROVIDER_CUSTOM ->
                    "填写自己的 OpenAI-compatible Base URL 和模型。局域网 Ollama、LM Studio、NAS 等接口可不填 API Key。";
            default ->
                    "OpenRouter 免费模型路由。默认使用 openrouter/free，由 OpenRouter 自动选择当前可用的免费模型。";
        };
    }

    private void refreshKeyState(String provider) {
        if (keyStateView == null) return;
        boolean keyOptional = AiConfigStore.PROVIDER_CUSTOM.equals(provider);
        keyStateView.setText(AiConfigStore.hasApiKey(this, provider)
                ? "状态：当前 Provider 已保存加密 API Key（输入框留空不会覆盖）"
                : keyOptional ? "状态：未保存 API Key · 自定义本地接口可留空"
                : "状态：未保存 API Key");
    }

    private boolean saveState(boolean toast) {
        String provider = selectedProvider();
        AiConfigStore.setProvider(this, provider);
        if (AiConfigStore.PROVIDER_CUSTOM.equals(provider)) {
            AiConfigStore.setBaseUrl(this, baseUrlEdit.getText().toString());
        }
        AiConfigStore.setModel(this, provider, modelEdit.getText().toString());
        AiConfigStore.setSystemPrompt(this, systemPromptEdit.getText().toString());
        String newKey = apiKeyEdit.getText() == null ? "" : apiKeyEdit.getText().toString().trim();
        if (!newKey.isEmpty()) {
            try {
                AiConfigStore.setApiKey(this, provider, newKey);
                apiKeyEdit.setText("");
            } catch (Throwable t) {
                Toast.makeText(this, "API Key 加密保存失败：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                return false;
            }
        }
        refreshKeyState(provider);
        updateStatus(provider);
        if (toast) Toast.makeText(this, "AI 设置已保存", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void testConnection() {
        if (!saveState(false)) return;
        String provider = selectedProvider();
        if (!AiConfigStore.isConfigured(this, provider)) {
            String message = AiConfigStore.PROVIDER_CUSTOM.equals(provider)
                    ? "请先填写 Base URL 和模型"
                    : "请先填写当前 Provider 的 API Key 和模型";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        testButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusView.setText("正在测试 " + AiConfigStore.providerLabel(provider) + "…");
        executor.execute(() -> {
            try {
                AiChatClient.Result result = AiChatClient.test(this);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    progress.setVisibility(View.GONE);
                    testButton.setEnabled(true);
                    statusView.setText("连接成功 · 模型：" + result.model + "\n返回：" + compact(result.text));
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    progress.setVisibility(View.GONE);
                    testButton.setEnabled(true);
                    statusView.setText("连接失败：" + messageOf(t));
                });
            }
        });
    }

    private void updateStatus(String provider) {
        if (statusView == null) return;
        String configured = AiConfigStore.isConfigured(this, provider) ? "已配置" : "未配置完整";
        String model = AiConfigStore.model(this, provider);
        statusView.setText(AiConfigStore.providerLabel(provider) + " · " + configured
                + "\n模型：" + (model.isBlank() ? "未设置" : model));
    }

    private String selectedProvider() {
        int index = providerSpinner == null ? 0 : providerSpinner.getSelectedItemPosition();
        if (index < 0 || index >= PROVIDERS.length) index = 0;
        return PROVIDERS[index];
    }

    private int providerIndex(String provider) {
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i].equals(provider)) return i;
        }
        return 0;
    }

    private void label(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setPadding(0, dp(14), 0, dp(4));
        root.addView(label);
    }

    private String compact(String text) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }

    private static String messageOf(Throwable t) {
        if (t == null) return "未知错误";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
