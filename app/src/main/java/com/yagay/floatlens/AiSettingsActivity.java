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

/** AI provider settings for OpenRouter Free and generic OpenAI-compatible endpoints. */
public final class AiSettingsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Spinner providerSpinner;
    private EditText baseUrlEdit;
    private EditText modelEdit;
    private EditText apiKeyEdit;
    private EditText systemPromptEdit;
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
        note.setText("支持 OpenRouter 免费模型路由和任意 OpenAI Compatible 接口。选中文字、OCR 结果或圈选文字后可以直接发送给 AI。API Key 使用 Android Keystore 加密保存在本机。");
        note.setTextSize(14);
        note.setAlpha(0.76f);
        note.setPadding(0, dp(8), 0, dp(18));
        root.addView(note);

        label(root, "提供商");
        providerSpinner = new Spinner(this);
        providerSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"OpenRouter Free", "自定义 OpenAI Compatible"}));
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                updateProviderUi(position == 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(providerSpinner);

        label(root, "Base URL");
        baseUrlEdit = new EditText(this);
        baseUrlEdit.setSingleLine(true);
        baseUrlEdit.setHint("https://example.com/v1");
        root.addView(baseUrlEdit, new LinearLayout.LayoutParams(-1, -2));

        label(root, "模型");
        modelEdit = new EditText(this);
        modelEdit.setSingleLine(true);
        modelEdit.setHint("openrouter/free");
        root.addView(modelEdit, new LinearLayout.LayoutParams(-1, -2));

        label(root, "API Key");
        apiKeyEdit = new EditText(this);
        apiKeyEdit.setSingleLine(true);
        apiKeyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyEdit.setHint("留空表示保留现有 Key；自定义本地接口可不填");
        root.addView(apiKeyEdit, new LinearLayout.LayoutParams(-1, -2));

        TextView keyState = new TextView(this);
        keyState.setId(android.R.id.summary);
        keyState.setTextSize(13);
        keyState.setAlpha(0.65f);
        keyState.setPadding(0, dp(2), 0, dp(10));
        root.addView(keyState);

        Button clearKey = new Button(this);
        clearKey.setText("清除已保存 API Key");
        clearKey.setOnClickListener(v -> {
            AiConfigStore.clearApiKey(this);
            apiKeyEdit.setText("");
            refreshKeyState(root);
            Toast.makeText(this, "API Key 已清除", Toast.LENGTH_SHORT).show();
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
        privacy.setText("隐私提示：使用在线 AI 时，你发送的文字/OCR 内容会传给所选服务商。自定义接口可以填写你自己的服务器地址。HTTP 地址仅建议用于你信任的局域网服务。");
        privacy.setTextSize(13);
        privacy.setAlpha(0.68f);
        privacy.setPadding(0, dp(22), 0, 0);
        root.addView(privacy);

        return scroll;
    }

    private void loadState() {
        boolean custom = AiConfigStore.PROVIDER_CUSTOM.equals(AiConfigStore.provider(this));
        providerSpinner.setSelection(custom ? 1 : 0);
        baseUrlEdit.setText(custom ? AiConfigStore.baseUrl(this) : AiConfigStore.defaultOpenRouterBase());
        modelEdit.setText(AiConfigStore.model(this));
        systemPromptEdit.setText(AiConfigStore.systemPrompt(this));
        updateProviderUi(custom);
        refreshKeyState((LinearLayout) ((ScrollView) findViewById(android.R.id.content).getRootView()).getChildAt(0));
        updateStatus();
    }

    private void refreshKeyState(LinearLayout ignored) {
        View root = ((View) apiKeyEdit.getParent());
        if (!(root instanceof LinearLayout parent)) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (v instanceof TextView tv && tv.getId() == android.R.id.summary) {
                tv.setText(AiConfigStore.hasApiKey(this)
                        ? "状态：已保存加密 API Key（输入框留空不会覆盖）"
                        : "状态：未保存 API Key");
                return;
            }
        }
    }

    private void updateProviderUi(boolean custom) {
        if (baseUrlEdit == null || modelEdit == null) return;
        baseUrlEdit.setEnabled(custom);
        if (!custom) {
            baseUrlEdit.setText(AiConfigStore.defaultOpenRouterBase());
            String current = modelEdit.getText() == null ? "" : modelEdit.getText().toString().trim();
            if (current.isEmpty()) modelEdit.setText(AiConfigStore.defaultOpenRouterModel());
        } else if (baseUrlEdit.getText() != null
                && AiConfigStore.defaultOpenRouterBase().equals(baseUrlEdit.getText().toString().trim())) {
            baseUrlEdit.setText("");
        }
    }

    private boolean saveState(boolean toast) {
        boolean custom = providerSpinner.getSelectedItemPosition() == 1;
        AiConfigStore.setProvider(this,
                custom ? AiConfigStore.PROVIDER_CUSTOM : AiConfigStore.PROVIDER_OPENROUTER);
        if (custom) AiConfigStore.setBaseUrl(this, baseUrlEdit.getText().toString());
        AiConfigStore.setModel(this, modelEdit.getText().toString());
        AiConfigStore.setSystemPrompt(this, systemPromptEdit.getText().toString());
        String newKey = apiKeyEdit.getText() == null ? "" : apiKeyEdit.getText().toString().trim();
        if (!newKey.isEmpty()) {
            try {
                AiConfigStore.setApiKey(this, newKey);
                apiKeyEdit.setText("");
            } catch (Throwable t) {
                Toast.makeText(this, "API Key 加密保存失败：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                return false;
            }
        }
        refreshKeyState(null);
        updateStatus();
        if (toast) Toast.makeText(this, "AI 设置已保存", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void testConnection() {
        if (!saveState(false)) return;
        if (!AiConfigStore.isConfigured(this)) {
            Toast.makeText(this, "请先填写必要的 Base URL、模型和 API Key", Toast.LENGTH_LONG).show();
            return;
        }
        testButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusView.setText("正在测试连接…");
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

    private void updateStatus() {
        if (statusView == null) return;
        String configured = AiConfigStore.isConfigured(this) ? "已配置" : "未配置完整";
        statusView.setText(AiConfigStore.providerLabel(this) + " · " + configured
                + "\n模型：" + (AiConfigStore.model(this).isBlank() ? "未设置" : AiConfigStore.model(this)));
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
