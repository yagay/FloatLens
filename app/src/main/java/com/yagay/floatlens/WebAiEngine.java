package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hidden in-process webpage engine for consumer AI sites.
 *
 * The WebView stays attached to FloatLens but off-screen, so the user keeps the native AI window.
 * It reuses FloatLens WebView cookies, fills the site's composer, submits the prompt, then polls the
 * page DOM for a new assistant message and returns that text to the native window.
 */
public final class WebAiEngine {
    public interface Listener {
        void onStatus(String text);
        void onResult(String target, String answer);
        void onNeedsLogin(String target, String message);
        void onError(String target, String message);
    }

    private static final long TIMEOUT_MS = 90_000L;
    private static final long STABLE_MS = 1_700L;
    private static final int MAX_COMPOSER_ATTEMPTS = 14;
    private static final int POLL_MS = 650;

    private final Context context;
    private final FrameLayout host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WebView webView;

    private String target = BrowserAiBridge.TARGET_CHATGPT;
    private String pendingPrompt = "";
    private String lastCandidate = "";
    private String lastDomDebug = "";
    private long candidateStableSince;
    private long startedAt;
    private int composerAttempts;
    private int emptyPolls;
    private boolean pageReady;
    private boolean waitingForPage;
    private boolean sending;
    private boolean destroyed;
    private final Set<String> baselineAnswers = new HashSet<>();

    private static final class DomSnapshot {
        final ArrayList<String> texts = new ArrayList<>();
        boolean generating;
        boolean sendReady;
        int modelCount;
        String debug = "";
    }

    public WebAiEngine(Context context, FrameLayout host, Listener listener) {
        this.context = context;
        this.host = host;
        this.listener = listener;
        this.webView = new WebView(context);
        configure();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(390), dp(700));
        host.addView(webView, lp);
        // Keep a real viewport so responsive sites render normal chat DOM, but make it effectively invisible.
        webView.setAlpha(0.01f);
        webView.setClickable(false);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configure() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                return blockExternalScheme(url);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockExternalScheme(url);
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageReady = false;
                waitingForPage = true;
                status("正在准备 " + EmbeddedWebAiActivity.targetLabel(target) + " 网页会话…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                pageReady = true;
                waitingForPage = false;
                if (sending && !pendingPrompt.isEmpty()) main.postDelayed(WebAiEngine.this::captureBaseline, 500L);
            }
        });
    }

    private boolean blockExternalScheme(String raw) {
        String url = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:"));
    }

    public boolean isSending() {
        return sending;
    }

    public void cancel() {
        sending = false;
        pendingPrompt = "";
        main.removeCallbacksAndMessages(null);
    }

    public void destroy() {
        destroyed = true;
        cancel();
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
        try {
            host.removeView(webView);
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (Throwable ignored) {}
    }

    public void send(String rawTarget, String rawPrompt) {
        if (destroyed) return;
        String nextTarget = EmbeddedWebAiActivity.normalizeTarget(rawTarget);
        String prompt = clean(rawPrompt);
        if (prompt.isEmpty()) {
            error(nextTarget, "没有可发送的文字");
            return;
        }

        cancel();
        sending = true;
        target = nextTarget;
        pendingPrompt = prompt;
        startedAt = SystemClock.uptimeMillis();
        composerAttempts = 0;
        emptyPolls = 0;
        baselineAnswers.clear();
        lastCandidate = "";
        lastDomDebug = "";
        candidateStableSince = 0L;

        String current = webView.getUrl();
        if (!pageReady || !sameTarget(current, target)) {
            waitingForPage = true;
            status("正在后台打开 " + EmbeddedWebAiActivity.targetLabel(target) + "…");
            webView.loadUrl(targetUrl(target));
        } else {
            status("正在使用已登录的 " + EmbeddedWebAiActivity.targetLabel(target) + " 网页会话…");
            captureBaseline();
        }
    }

    private void captureBaseline() {
        if (!active()) return;
        evaluate(candidateScript(), raw -> {
            if (!active()) return;
            DomSnapshot snapshot = parseSnapshot(raw);
            baselineAnswers.clear();
            baselineAnswers.addAll(snapshot.texts);
            lastDomDebug = snapshot.debug;
            tryComposer();
        });
    }

    private void tryComposer() {
        if (!active()) return;
        if (timedOut()) {
            fail("等待网页输入框超时" + diagnosticSuffix());
            return;
        }
        composerAttempts++;
        String quoted = JSONObject.quote(pendingPrompt);
        String js;
        if (isGemini()) {
            js = "(function(){try{" +
                    "var text=" + quoted + ";" +
                    "var sels=['rich-textarea .ql-editor','rich-textarea [contenteditable=\\\"true\\\"]','.textarea[contenteditable=\\\"true\\\"]','.textarea','textarea','[contenteditable=\\\"true\\\"]'];" +
                    "var el=null;for(var i=0;i<sels.length&&!el;i++){var list=document.querySelectorAll(sels[i]);for(var j=list.length-1;j>=0;j--){var x=list[j];var r=x.getBoundingClientRect();if(!x.disabled&&r.width>20&&r.height>10){el=x;break;}}}" +
                    "if(!el)return 'missing';el.focus();" +
                    "if(el.isContentEditable){el.textContent=text;try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){el.dispatchEvent(new Event('input',{bubbles:true}));}}" +
                    "else{var p=Object.getPrototypeOf(el),d=Object.getOwnPropertyDescriptor(p,'value');if(!d||!d.set){d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')||Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');}if(d&&d.set)d.set.call(el,text);else el.value=text;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
                    "return 'filled';}catch(e){return 'error:'+String(e);}})();";
        } else {
            js = "(function(){try{" +
                    "var text=" + quoted + ";" +
                    "var sels=['#prompt-textarea','textarea[placeholder]','textarea','[contenteditable=\\\"true\\\"][data-lexical-editor=\\\"true\\\"]','div.ProseMirror[contenteditable=\\\"true\\\"]','rich-textarea [contenteditable=\\\"true\\\"]','[contenteditable=\\\"true\\\"]'];" +
                    "var el=null;for(var i=0;i<sels.length&&!el;i++){var list=document.querySelectorAll(sels[i]);for(var j=list.length-1;j>=0;j--){var x=list[j];if(!x.disabled){el=x;break;}}}" +
                    "if(!el)return 'missing';el.focus();" +
                    "if(el.isContentEditable){el.textContent=text;try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){el.dispatchEvent(new Event('input',{bubbles:true}));}}" +
                    "else{var p=Object.getPrototypeOf(el),d=Object.getOwnPropertyDescriptor(p,'value');if(!d||!d.set){d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')||Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');}if(d&&d.set)d.set.call(el,text);else el.value=text;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
                    "return 'filled';}catch(e){return 'error:'+String(e);}})();";
        }
        evaluate(js, raw -> {
            if (!active()) return;
            String value = decodeJsString(raw);
            if (value.startsWith("filled")) {
                status("已在后台网页填入问题，正在发送…");
                main.postDelayed(this::trySend, 320L);
                return;
            }
            if (value.startsWith("error")) {
                fail("网页输入失败: " + value);
                return;
            }
            if (composerAttempts >= MAX_COMPOSER_ATTEMPTS) {
                needsLogin("没有找到聊天输入框。可能尚未登录、需要验证码，或该网站暂时不允许 WebView 自动操作。" + diagnosticSuffix());
                return;
            }
            status("等待 " + EmbeddedWebAiActivity.targetLabel(target) + " 登录/输入框…");
            main.postDelayed(this::tryComposer, 700L);
        });
    }

    private void trySend() {
        if (!active()) return;
        String js;
        if (isGemini()) {
            js = "(function(){try{" +
                    "var sels=['gem-icon-button.submit[aria-disabled=\\\"false\\\"]','button.send-button:not([disabled])','button.submit:not([disabled])','button[aria-label=\\\"Send message\\\"]:not([disabled])','button[aria-label*=\\\"Send\\\"]:not([disabled])','button[aria-label*=\\\"发送\\\"]:not([disabled])'];" +
                    "var b=null;for(var i=0;i<sels.length&&!b;i++){var list=document.querySelectorAll(sels[i]);for(var j=list.length-1;j>=0;j--){var x=list[j];var r=x.getBoundingClientRect();if(r.width>8&&r.height>8){b=x;break;}}}" +
                    "if(b){b.click();return 'sent-gemini';}" +
                    "var e=document.querySelector('rich-textarea .ql-editor,rich-textarea [contenteditable=\\\"true\\\"],.textarea[contenteditable=\\\"true\\\"],textarea,[contenteditable=\\\"true\\\"]');" +
                    "if(e){try{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));e.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return 'sent-key';}catch(ignore){}}" +
                    "return 'nosend';}catch(e){return 'error:'+String(e);}})();";
        } else {
            js = "(function(){try{" +
                    "var sels=['button[data-testid=\\\"send-button\\\"]','button[data-testid*=\\\"send\\\"]','button[aria-label*=\\\"Send\\\"]','button[aria-label*=\\\"send\\\"]','button[aria-label*=\\\"发送\\\"]','button[type=\\\"submit\\\"]'];" +
                    "var b=null;for(var i=0;i<sels.length&&!b;i++){var list=document.querySelectorAll(sels[i]);for(var j=list.length-1;j>=0;j--){var x=list[j];if(!x.disabled){b=x;break;}}}" +
                    "if(b){b.click();return 'sent';}" +
                    "var e=document.querySelector('#prompt-textarea,textarea,[contenteditable=\\\"true\\\"]');var f=e&&e.closest?e.closest('form'):null;if(f&&f.requestSubmit){f.requestSubmit();return 'sent-form';}" +
                    "if(e){try{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));e.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));return 'sent-key';}catch(ignore){}}" +
                    "return 'nosend';}catch(e){return 'error:'+String(e);}})();";
        }
        evaluate(js, raw -> {
            if (!active()) return;
            String value = decodeJsString(raw);
            if (value.startsWith("sent")) {
                status("已发送，正在后台读取网页回答…");
                emptyPolls = 0;
                main.postDelayed(this::pollAnswer, 900L);
            } else if (value.startsWith("error")) {
                fail("网页发送失败: " + value);
            } else {
                needsLogin("文字已经填入，但没有可靠找到发送按钮。请打开一次网页登录页面确认登录状态。" + diagnosticSuffix());
            }
        });
    }

    private void pollAnswer() {
        if (!active()) return;
        if (timedOut()) {
            fail("等待网页回答超时" + diagnosticSuffix());
            return;
        }
        evaluate(candidateScript(), raw -> {
            if (!active()) return;
            DomSnapshot snapshot = parseSnapshot(raw);
            lastDomDebug = snapshot.debug;
            String candidate = chooseNewAnswer(snapshot.texts);
            long now = SystemClock.uptimeMillis();

            if (candidate.isEmpty()) {
                emptyPolls++;
                if (isGemini() && snapshot.modelCount > 0) {
                    status(snapshot.generating
                            ? "Gemini 正在生成回答…"
                            : "Gemini 已有回答节点，正在读取内容…");
                } else if (isGemini() && emptyPolls >= 5) {
                    status("Gemini 已发送，但还没有检测到新的 model-response…");
                } else {
                    status("等待 " + EmbeddedWebAiActivity.targetLabel(target) + " 回答…");
                }
                main.postDelayed(this::pollAnswer, POLL_MS);
                return;
            }

            emptyPolls = 0;
            if (!candidate.equals(lastCandidate)) {
                lastCandidate = candidate;
                candidateStableSince = now;
                status(snapshot.generating ? "Gemini 正在生成回答…" : "正在读取网页回答…");
                main.postDelayed(this::pollAnswer, POLL_MS);
                return;
            }

            if (snapshot.generating) {
                status("Gemini 正在生成回答…");
                main.postDelayed(this::pollAnswer, POLL_MS);
                return;
            }

            if (now - candidateStableSince >= STABLE_MS || (isGemini() && snapshot.sendReady)) {
                String answer = candidate;
                String doneTarget = target;
                sending = false;
                pendingPrompt = "";
                status(EmbeddedWebAiActivity.targetLabel(doneTarget) + " 网页回答已返回到窗口");
                if (listener != null) listener.onResult(doneTarget, answer);
            } else {
                main.postDelayed(this::pollAnswer, POLL_MS);
            }
        });
    }

    private String chooseNewAnswer(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        String prompt = compact(pendingPrompt);
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String text = compact(candidates.get(i));
            if (text.length() < 2) continue;
            if (text.equals(prompt)) continue;
            if (baselineAnswers.contains(text)) continue;
            if (looksLikeUiChrome(text)) continue;
            return text;
        }
        return "";
    }

    private boolean looksLikeUiChrome(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        if (s.length() > 120) return false;
        return s.equals("send") || s.equals("发送") || s.equals("stop") || s.equals("停止")
                || s.contains("log in") || s.contains("sign up") || s.contains("登录")
                || s.contains("new chat") || s.contains("新对话") || s.contains("regenerate");
    }

    private String candidateScript() {
        if (isGemini()) {
            return "(function(){try{" +
                    "var models=Array.from(document.querySelectorAll('model-response'));" +
                    "var out=[],seen={};models.forEach(function(m){var c=m.querySelector('message-content.model-response-text,.model-response-text,.message-content,.response-content,.markdown.markdown-main-panel,.markdown')||m;var t=(c.innerText||c.textContent||'').trim();if(t.length>1&&t.length<30000&&!seen[t]){seen[t]=1;out.push(t);}});" +
                    "if(!out.length){var fall=document.querySelectorAll('message-content.model-response-text,.model-response-text,.response-content');for(var i=0;i<fall.length;i++){var t=(fall[i].innerText||fall[i].textContent||'').trim();if(t.length>1&&t.length<30000&&!seen[t]){seen[t]=1;out.push(t);}}}" +
                    "var generating=false;var controls=document.querySelectorAll('button,[role=\\\"button\\\"],gem-icon-button');for(var k=0;k<controls.length;k++){var x=controls[k],a=((x.getAttribute('aria-label')||'')+' '+(x.textContent||'')+' '+(x.className||'')).toLowerCase();if(a.indexOf('stop')>=0||a.indexOf('停止')>=0){var r=x.getBoundingClientRect();if(r.width>0&&r.height>0&&x.getAttribute('aria-disabled')!=='true'&&!x.disabled){generating=true;break;}}}" +
                    "var sendReady=!!document.querySelector('gem-icon-button.submit[aria-disabled=\\\"false\\\"],button.send-button:not([disabled]),button.submit:not([disabled]),button[aria-label=\\\"Send message\\\"]:not([disabled])');" +
                    "return JSON.stringify({texts:out.slice(-20),generating:generating,sendReady:sendReady,modelCount:models.length,debug:'gemini models='+models.length+',texts='+out.length+',generating='+generating+',sendReady='+sendReady});" +
                    "}catch(e){return JSON.stringify({texts:[],generating:false,sendReady:false,modelCount:0,debug:'gemini script error:'+String(e)});}})();";
        }
        return "(function(){try{" +
                "var sels=['[data-message-author-role=\\\"assistant\\\"]','[data-testid=\\\"assistant-message\\\"]','[data-testid*=\\\"assistant\\\"]','.model-response-text','message-content','model-response','.ds-markdown','.markdown','.prose','article'];" +
                "var out=[],seen={};for(var i=0;i<sels.length;i++){var list=document.querySelectorAll(sels[i]);for(var j=0;j<list.length;j++){var x=list[j],t=(x.innerText||x.textContent||'').trim();if(t.length>1&&t.length<30000&&!seen[t]){seen[t]=1;out.push(t);}}}" +
                "return JSON.stringify({texts:out.slice(-40),generating:false,sendReady:false,modelCount:0,debug:'generic texts='+out.length});}catch(e){return JSON.stringify({texts:[],generating:false,sendReady:false,modelCount:0,debug:'generic script error:'+String(e)});}})();";
    }

    private DomSnapshot parseSnapshot(String raw) {
        DomSnapshot snapshot = new DomSnapshot();
        try {
            String json = decodeJsString(raw);
            Object parsed = new JSONTokener(json).nextValue();
            JSONArray texts;
            if (parsed instanceof JSONObject) {
                JSONObject o = (JSONObject) parsed;
                texts = o.optJSONArray("texts");
                snapshot.generating = o.optBoolean("generating", false);
                snapshot.sendReady = o.optBoolean("sendReady", false);
                snapshot.modelCount = o.optInt("modelCount", 0);
                snapshot.debug = o.optString("debug", "");
            } else if (parsed instanceof JSONArray) {
                texts = (JSONArray) parsed;
            } else {
                texts = null;
            }
            if (texts != null) {
                for (int i = 0; i < texts.length(); i++) {
                    String value = compact(texts.optString(i, ""));
                    if (!value.isEmpty()) snapshot.texts.add(value);
                }
            }
        } catch (Throwable t) {
            snapshot.debug = "parse error: " + messageOf(t);
        }
        return snapshot;
    }

    private void evaluate(String js, android.webkit.ValueCallback<String> callback) {
        if (destroyed) return;
        try { webView.evaluateJavascript(js, callback); }
        catch (Throwable t) { fail("网页脚本执行失败: " + messageOf(t)); }
    }

    private boolean active() {
        return !destroyed && sending && !pendingPrompt.isEmpty();
    }

    private boolean timedOut() {
        return SystemClock.uptimeMillis() - startedAt > TIMEOUT_MS;
    }

    private boolean isGemini() {
        return BrowserAiBridge.TARGET_GEMINI.equals(EmbeddedWebAiActivity.normalizeTarget(target));
    }

    private String diagnosticSuffix() {
        return lastDomDebug == null || lastDomDebug.isBlank() ? "" : "（" + lastDomDebug + "）";
    }

    private void needsLogin(String message) {
        if (!sending) return;
        sending = false;
        status("需要处理网页登录");
        if (listener != null) listener.onNeedsLogin(target, message);
    }

    private void fail(String message) {
        if (!sending) return;
        String failedTarget = target;
        sending = false;
        status("网页 AI 失败");
        if (listener != null) listener.onError(failedTarget, message);
    }

    private void error(String failedTarget, String message) {
        if (listener != null) listener.onError(failedTarget, message);
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }

    private boolean sameTarget(String url, String target) {
        if (url == null || url.isEmpty()) return false;
        String host;
        try { host = android.net.Uri.parse(url).getHost(); }
        catch (Throwable t) { host = null; }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        switch (EmbeddedWebAiActivity.normalizeTarget(target)) {
            case BrowserAiBridge.TARGET_GEMINI: return host.contains("gemini.google.com");
            case BrowserAiBridge.TARGET_CLAUDE: return host.contains("claude.ai");
            case BrowserAiBridge.TARGET_GROK: return host.contains("grok.com") || host.contains("x.com");
            case BrowserAiBridge.TARGET_DEEPSEEK: return host.contains("chat.deepseek.com") || host.contains("deepseek.com");
            default: return host.contains("chatgpt.com") || host.contains("chat.openai.com");
        }
    }

    private String targetUrl(String target) {
        switch (EmbeddedWebAiActivity.normalizeTarget(target)) {
            case BrowserAiBridge.TARGET_GEMINI: return "https://gemini.google.com/app";
            case BrowserAiBridge.TARGET_CLAUDE: return "https://claude.ai/new";
            case BrowserAiBridge.TARGET_GROK: return "https://grok.com/";
            case BrowserAiBridge.TARGET_DEEPSEEK: return "https://chat.deepseek.com/";
            default: return "https://chatgpt.com/";
        }
    }

    private static String decodeJsString(String raw) {
        if (raw == null || raw.equals("null")) return "";
        try {
            Object value = new JSONTokener(raw).nextValue();
            return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
        } catch (Throwable t) {
            return raw.replace("\\\"", "\"").replace("\\n", "\n");
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    private static String compact(String text) {
        String value = clean(text).replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\\n\\n");
        return value.length() > 30_000 ? value.substring(0, 30_000) : value;
    }

    private static String messageOf(Throwable t) {
        if (t == null) return "未知错误";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
