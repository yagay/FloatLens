package com.yagay.floatlens;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/**
 * In-app webpage mode for consumer AI sites. It keeps the webpage, login flow and cookies inside
 * FloatLens' WebView and never hands normal http/https navigation to an external browser.
 */
public final class EmbeddedWebAiActivity extends AppCompatActivity {
    public static final String EXTRA_TARGET = "floatlens_web_ai_target";
    public static final String EXTRA_PROMPT = "floatlens_web_ai_prompt";

    private static final String PREFS = "floatlens_web_ai";
    private static final String KEY_TARGET = "target";
    private static final int MAX_INJECT_ATTEMPTS = 12;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private TextView statusView;
    private Button targetButton;
    private String target = BrowserAiBridge.TARGET_CHATGPT;
    private String pendingPrompt = "";
    private int injectAttempts;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        target = normalizeTarget(getIntent().getStringExtra(EXTRA_TARGET));
        pendingPrompt = clean(getIntent().getStringExtra(EXTRA_PROMPT));
        if (getIntent().getStringExtra(EXTRA_TARGET) == null) {
            target = normalizeTarget(prefs().getString(KEY_TARGET, BrowserAiBridge.TARGET_CHATGPT));
        }
        prefs().edit().putString(KEY_TARGET, target).apply();
        setTitle("网页 AI · " + targetLabel(target));
        setContentView(buildUi());
        configureWebView();
        loadTargetHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String nextTarget = normalizeTarget(intent.getStringExtra(EXTRA_TARGET));
        String nextPrompt = clean(intent.getStringExtra(EXTRA_PROMPT));
        if (!nextPrompt.isEmpty()) pendingPrompt = nextPrompt;
        if (!nextTarget.equals(target)) {
            target = nextTarget;
            prefs().edit().putString(KEY_TARGET, target).apply();
            updateTargetUi();
            loadTargetHome();
        } else if (!pendingPrompt.isEmpty()) {
            injectAttempts = 0;
            schedulePromptInjection(650L);
        }
    }

    @Override protected void onPause() {
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebViewClient(null);
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        Button close = new Button(this);
        close.setText("返回");
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(0, -2, 0.8f));

        targetButton = new Button(this);
        targetButton.setOnClickListener(v -> showTargetPicker());
        bar.addView(targetButton, new LinearLayout.LayoutParams(0, -2, 1.3f));

        Button resend = new Button(this);
        resend.setText("重发");
        resend.setOnClickListener(v -> {
            String prompt = clean(getIntent().getStringExtra(EXTRA_PROMPT));
            if (prompt.isEmpty()) prompt = pendingPrompt;
            if (prompt.isEmpty()) {
                Toast.makeText(this, "没有待发送的文字", Toast.LENGTH_SHORT).show();
                return;
            }
            pendingPrompt = prompt;
            injectAttempts = 0;
            schedulePromptInjection(100L);
        });
        bar.addView(resend, new LinearLayout.LayoutParams(0, -2, 0.8f));

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> { if (webView != null) webView.reload(); });
        bar.addView(refresh, new LinearLayout.LayoutParams(0, -2, 0.8f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setPadding(dp(10), dp(2), dp(10), dp(6));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        updateTargetUi();
        return root;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return keepInside(view, request == null || request.getUrl() == null
                        ? "" : request.getUrl().toString());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return keepInside(view, url);
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                status("正在加载 " + targetLabel(target) + "…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                injectAttempts = 0;
                if (pendingPrompt.isEmpty()) {
                    status("已在 FloatLens 内打开 " + targetLabel(target)
                            + "。首次使用请在这里登录；登录状态会保存在本机 WebView。" );
                } else {
                    status("页面已加载，正在尝试自动填入并发送…");
                    schedulePromptInjection(700L);
                }
            }
        });
    }

    /** Return true only when we consumed a non-http navigation ourselves. */
    private boolean keepInside(WebView view, String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) {
            return false;
        }
        if (url.startsWith("intent:")) {
            try {
                Intent parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = parsed.getStringExtra("browser_fallback_url");
                if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                    view.loadUrl(fallback);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "这个网页登录步骤需要外部应用，FloatLens 已阻止外跳", Toast.LENGTH_LONG).show();
        return true;
    }

    private void loadTargetHome() {
        injectAttempts = 0;
        updateTargetUi();
        status("正在 FloatLens 内打开 " + targetLabel(target) + "…");
        webView.loadUrl(targetUrl(target));
    }

    private void showTargetPicker() {
        new AlertDialog.Builder(this)
                .setTitle("网页 AI")
                .setItems(BrowserAiBridge.TARGET_LABELS, (dialog, which) -> {
                    if (which < 0 || which >= BrowserAiBridge.TARGET_IDS.length) return;
                    target = normalizeTarget(BrowserAiBridge.TARGET_IDS[which]);
                    prefs().edit().putString(KEY_TARGET, target).apply();
                    loadTargetHome();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void schedulePromptInjection(long delayMs) {
        main.postDelayed(this::tryFillPrompt, delayMs);
    }

    private void tryFillPrompt() {
        if (destroyed || webView == null || pendingPrompt.isEmpty()) return;
        if (++injectAttempts > MAX_INJECT_ATTEMPTS) {
            status("没有自动找到输入框。请先完成登录，或直接在当前内置网页里手动粘贴/发送；不会跳到外部浏览器。");
            return;
        }
        String quoted = JSONObject.quote(pendingPrompt);
        String js = "(function(){try{" +
                "var text=" + quoted + ";" +
                "var sels=['#prompt-textarea','textarea','[contenteditable=\\\"true\\\"][data-lexical-editor=\\\"true\\\"]','div.ProseMirror[contenteditable=\\\"true\\\"]','rich-textarea [contenteditable=\\\"true\\\"]','[contenteditable=\\\"true\\\"]'];" +
                "var el=null;for(var i=0;i<sels.length;i++){var list=document.querySelectorAll(sels[i]);for(var j=list.length-1;j>=0;j--){var x=list[j];var r=x.getBoundingClientRect();if(r.width>20&&r.height>10){el=x;break;}}if(el)break;}" +
                "if(!el)return 'missing';el.focus();" +
                "if(el.isContentEditable){el.textContent=text;try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){el.dispatchEvent(new Event('input',{bubbles:true}));}}" +
                "else{var p=Object.getPrototypeOf(el),d=Object.getOwnPropertyDescriptor(p,'value');if(!d||!d.set){d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')||Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');}if(d&&d.set)d.set.call(el,text);else el.value=text;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
                "return 'filled';}catch(e){return 'error:'+String(e);}})();";
        webView.evaluateJavascript(js, result -> {
            if (destroyed || pendingPrompt.isEmpty()) return;
            String value = result == null ? "" : result;
            if (value.contains("filled")) {
                status("已自动填入，正在尝试发送…");
                main.postDelayed(this::tryClickSend, 350L);
            } else if (value.contains("error")) {
                status("自动填入失败；你仍可直接在内置网页里手动输入。" );
            } else {
                status("等待网页输入框…如果正在登录，请完成登录。" );
                schedulePromptInjection(700L);
            }
        });
    }

    private void tryClickSend() {
        if (destroyed || webView == null || pendingPrompt.isEmpty()) return;
        String js = "(function(){try{" +
                "var sels=['button[data-testid=\\\"send-button\\\"]','button[data-testid*=\\\"send\\\"]','button[aria-label*=\\\"Send\\\"]','button[aria-label*=\\\"send\\\"]','button[aria-label*=\\\"发送\\\"]','button[type=\\\"submit\\\"]'];" +
                "var b=null;for(var i=0;i<sels.length;i++){var list=document.querySelectorAll(sels[i]);for(var j=list.length-1;j>=0;j--){var x=list[j];var r=x.getBoundingClientRect();if(!x.disabled&&r.width>10&&r.height>10){b=x;break;}}if(b)break;}" +
                "if(!b){var e=document.querySelector('#prompt-textarea,textarea,[contenteditable=\\\"true\\\"]');var f=e&&e.closest?e.closest('form'):null;if(f&&f.requestSubmit){f.requestSubmit();return 'sent-form';}return 'nosend';}" +
                "b.click();return 'sent';}catch(e){return 'error:'+String(e);}})();";
        webView.evaluateJavascript(js, result -> {
            if (destroyed) return;
            String value = result == null ? "" : result;
            if (value.contains("sent")) {
                pendingPrompt = "";
                status("已发送到 " + targetLabel(target) + "。回答会直接显示在这个 FloatLens 内置网页里。" );
            } else {
                pendingPrompt = "";
                status("文字已经填入，但没有可靠找到发送按钮。请在当前内置网页点一下发送；不会跳浏览器。" );
                Toast.makeText(this, "已填入文字，请手动点发送", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateTargetUi() {
        setTitle("网页 AI · " + targetLabel(target));
        if (targetButton != null) targetButton.setText(targetLabel(target));
    }

    private void status(String text) {
        if (statusView != null) statusView.setText(text == null ? "" : text);
    }

    public static String normalizeTarget(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (String id : BrowserAiBridge.TARGET_IDS) if (id.equals(value)) return id;
        return BrowserAiBridge.TARGET_CHATGPT;
    }

    public static String targetLabel(String target) {
        String id = normalizeTarget(target);
        for (int i = 0; i < BrowserAiBridge.TARGET_IDS.length; i++) {
            if (id.equals(BrowserAiBridge.TARGET_IDS[i])) return BrowserAiBridge.TARGET_LABELS[i];
        }
        return "ChatGPT";
    }

    private static String targetUrl(String target) {
        return switch (normalizeTarget(target)) {
            case BrowserAiBridge.TARGET_GEMINI -> "https://gemini.google.com/app";
            case BrowserAiBridge.TARGET_CLAUDE -> "https://claude.ai/new";
            case BrowserAiBridge.TARGET_GROK -> "https://grok.com/";
            case BrowserAiBridge.TARGET_DEEPSEEK -> "https://chat.deepseek.com/";
            default -> "https://chatgpt.com/";
        };
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
