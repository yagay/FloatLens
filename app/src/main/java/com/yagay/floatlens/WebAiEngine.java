package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
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
 * Hidden webpage engine used by the native FloatLens AI window.
 *
 * ChatGPT, Gemini and DeepSeek intentionally share one state machine and one automation pipeline.
 * Provider-specific code is reduced to selector profiles. The engine always has generic fallbacks,
 * so a small website DOM change does not immediately break the entire provider.
 */
public final class WebAiEngine {
    public interface Listener {
        void onStatus(String text);
        void onResult(String target, String answer);
        void onNeedsLogin(String target, String message);
        void onError(String target, String message);
    }

    private enum Stage {
        IDLE,
        LOADING,
        PREPARING,
        SENDING,
        WAITING
    }

    private static final long TIMEOUT_MS = 90_000L;
    private static final int MAX_COMPOSER_ATTEMPTS = 16;
    private static final int MAX_SEND_ATTEMPTS = 16;
    private static final int POLL_MS = 650;
    private static final long DEBUG_LOG_INTERVAL_MS = 2_500L;
    private static final String SESSION_PREFS = "floatlens_web_ai_sessions";
    private static final String SESSION_PREFIX = "conversation_url_";

    private static final String[] COMMON_COMPOSERS = {
            "#prompt-textarea",
            "[data-testid=\"prompt-textarea\"]",
            "textarea[placeholder]",
            "textarea",
            "[contenteditable=\"true\"][role=\"textbox\"]",
            "[contenteditable=\"true\"][data-lexical-editor=\"true\"]",
            "div.ProseMirror[contenteditable=\"true\"]",
            "[contenteditable=\"true\"]"
    };

    private static final String[] COMMON_SENDERS = {
            "button[data-testid=\"send-button\"]",
            "button[data-testid*=\"send\"]",
            "button[aria-label*=\"Send\"]",
            "button[aria-label*=\"send\"]",
            "button[aria-label*=\"发送\"]",
            "button[type=\"submit\"]"
    };

    private static final String[] COMMON_ANSWERS = {
            "[data-message-author-role=\"assistant\"]",
            "[data-testid=\"assistant-message\"]",
            "[data-testid*=\"assistant\"]",
            "model-response",
            "message-content.model-response-text",
            ".model-response-text",
            ".response-content",
            ".ds-markdown:not(.ds-markdown--think)",
            ".markdown",
            ".prose"
    };

    private static final String[] COMMON_IGNORES = {
            "nav",
            "header",
            "footer",
            "aside",
            "[role=\"navigation\"]",
            "[class*=\"reasoning\"]",
            "[class*=\"think-content\"]",
            ".ds-markdown--think"
    };

    private static final class ProviderProfile {
        final String id;
        final String[] composers;
        final String[] senders;
        final String[] answers;
        final String[] ignores;
        final long stableMs;

        ProviderProfile(String id, String[] composers, String[] senders,
                        String[] answers, String[] ignores, long stableMs) {
            this.id = id;
            this.composers = composers;
            this.senders = senders;
            this.answers = answers;
            this.ignores = ignores;
            this.stableMs = stableMs;
        }
    }

    private static final ProviderProfile CHATGPT = new ProviderProfile(
            BrowserAiBridge.TARGET_CHATGPT,
            new String[]{
                    "#prompt-textarea",
                    "[data-testid=\"prompt-textarea\"]",
                    "[contenteditable=\"true\"][role=\"textbox\"]",
                    "[aria-label=\"Chat with ChatGPT\"]",
                    "[aria-label=\"与 ChatGPT 聊天\"]",
                    "[placeholder=\"Ask anything\"]",
                    "[placeholder=\"有问题，尽管问\"]"
            },
            new String[]{
                    "button[data-testid=\"send-button\"]",
                    "#composer-submit-button",
                    "button[aria-label=\"Send prompt\"]",
                    "button[aria-label=\"Send message\"]",
                    "button[aria-label=\"发送\"]",
                    "button[aria-label=\"发送消息\"]"
            },
            new String[]{
                    "[data-message-author-role=\"assistant\"]",
                    "article[data-testid*=\"conversation-turn\"] [data-message-author-role=\"assistant\"]",
                    "article[data-testid*=\"conversation-turn\"] .markdown",
                    "[data-testid*=\"assistant\"] .markdown",
                    "[data-testid*=\"assistant\"]"
            },
            new String[]{},
            3_200L
    );

    private static final ProviderProfile GEMINI = new ProviderProfile(
            BrowserAiBridge.TARGET_GEMINI,
            new String[]{
                    "rich-textarea .ql-editor",
                    "rich-textarea [contenteditable=\"true\"]",
                    ".textarea[contenteditable=\"true\"]",
                    ".textarea",
                    "textarea"
            },
            new String[]{
                    "gem-icon-button.submit[aria-disabled=\"false\"]",
                    "button.send-button:not([disabled])",
                    "button.submit:not([disabled])",
                    "button[aria-label=\"Send message\"]:not([disabled])",
                    "button[aria-label*=\"Send\"]:not([disabled])",
                    "button[aria-label*=\"发送\"]:not([disabled])"
            },
            new String[]{
                    "model-response message-content.model-response-text",
                    "model-response .model-response-text",
                    "model-response message-content",
                    "model-response .response-content",
                    "model-response",
                    ".model-response-text",
                    ".response-content"
            },
            new String[]{},
            2_400L
    );

    private static final ProviderProfile DEEPSEEK = new ProviderProfile(
            BrowserAiBridge.TARGET_DEEPSEEK,
            new String[]{
                    "textarea#chat-input",
                    "textarea.chat-input",
                    "textarea.message-input-textarea",
                    ".ds-textarea textarea",
                    "textarea[placeholder]",
                    "textarea",
                    "div[contenteditable=\"true\"]"
            },
            new String[]{
                    ".ds-textarea-send-button",
                    "div[class*=\"send-button\"]",
                    "button[class*=\"send-button\"]",
                    "[class*=\"sendBtn\"]",
                    "button[aria-label*=\"Send\"]",
                    "button[aria-label*=\"发送\"]",
                    "button[type=\"submit\"]"
            },
            new String[]{
                    ".ds-markdown:not(.ds-markdown--think)",
                    "[class*=\"message\"] .ds-markdown:not(.ds-markdown--think)",
                    "[class*=\"response\"] .ds-markdown:not(.ds-markdown--think)"
            },
            new String[]{
                    ".ds-markdown--think",
                    "[class*=\"reasoning\"]",
                    "[class*=\"think-content\"]"
            },
            2_700L
    );

    private static final ProviderProfile CLAUDE = new ProviderProfile(
            BrowserAiBridge.TARGET_CLAUDE,
            new String[]{
                    "div.ProseMirror[contenteditable=\"true\"]",
                    "[contenteditable=\"true\"][data-lexical-editor=\"true\"]",
                    "textarea"
            },
            new String[]{
                    "button[aria-label*=\"Send\"]",
                    "button[data-testid*=\"send\"]",
                    "button[type=\"submit\"]"
            },
            new String[]{
                    "[data-testid*=\"assistant\"]",
                    ".font-claude-message",
                    ".prose"
            },
            new String[]{},
            2_500L
    );

    private static final ProviderProfile GROK = new ProviderProfile(
            BrowserAiBridge.TARGET_GROK,
            new String[]{
                    "textarea",
                    "[contenteditable=\"true\"][role=\"textbox\"]",
                    "[contenteditable=\"true\"]"
            },
            new String[]{
                    "button[aria-label*=\"Send\"]",
                    "button[data-testid*=\"send\"]",
                    "button[type=\"submit\"]"
            },
            new String[]{
                    "[data-testid*=\"assistant\"]",
                    "[data-message-author-role=\"assistant\"]",
                    ".prose"
            },
            new String[]{},
            2_500L
    );

    private final Context context;
    private final FrameLayout host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WebView webView;

    private String target = BrowserAiBridge.TARGET_CHATGPT;
    private String pendingPrompt = "";
    private String lastCandidate = "";
    private String lastDomDebug = "";
    private String lastLoggedDebug = "";
    private long candidateStableSince;
    private long startedAt;
    private long lastDebugLogAt;
    private int composerAttempts;
    private int sendAttempts;
    private int emptyPolls;
    private int baselinePrimaryCount;
    private int baselineFallbackCount;
    private boolean pageReady;
    private boolean sending;
    private boolean destroyed;
    private Stage stage = Stage.IDLE;
    private final Set<String> baselinePrimary = new HashSet<>();
    private final Set<String> baselineFallback = new HashSet<>();

    private static final class DomSnapshot {
        final ArrayList<String> primaryTexts = new ArrayList<>();
        final ArrayList<String> fallbackTexts = new ArrayList<>();
        boolean generating;
        boolean sendReady;
        boolean composerReady;
        int primaryCount;
        int fallbackCount;
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
        webView.setAlpha(0.01f);
        webView.setClickable(false);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        diag("INIT", "engine created");
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
                diag("PAGE_START", "stage=" + stage + " url=" + safePath(url));
                if (sending && stage == Stage.LOADING) {
                    status("正在准备 " + label() + " 网页会话…");
                } else if (sending && stage == Stage.WAITING) {
                    status(label() + " 已发送，正在进入对话页面…");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                pageReady = true;
                rememberConversationUrl(target, url);
                diag("PAGE_FINISH", "stage=" + stage + " url=" + safePath(url));
                if (!sending || pendingPrompt.isEmpty()) return;

                // Critical: a send can navigate from the home page to a conversation URL.
                // Never recapture the baseline or resend after we have entered WAITING.
                if (stage == Stage.LOADING) {
                    stage = Stage.PREPARING;
                    main.postDelayed(WebAiEngine.this::captureBaseline, 500L);
                } else if (stage == Stage.WAITING) {
                    main.postDelayed(WebAiEngine.this::pollAnswer, 450L);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                String message = error == null ? "unknown" : String.valueOf(error.getDescription());
                diag("WEB_ERROR", "url=" + safePath(url) + " error=" + message);
                super.onReceivedError(view, request, error);
            }
        });
    }

    private boolean blockExternalScheme(String raw) {
        String url = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        boolean blocked = !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:"));
        if (blocked) diag("BLOCK_URL", "scheme blocked");
        return blocked;
    }

    public boolean isSending() {
        return sending;
    }

    public void cancel() {
        if (sending) diag("CANCEL", "stage=" + stage);
        sending = false;
        pendingPrompt = "";
        stage = Stage.IDLE;
        main.removeCallbacksAndMessages(null);
    }

    /** Forget only this provider's FloatLens conversation and open its clean/new-chat entry page. */
    public void startNewConversation(String rawTarget) {
        if (destroyed) return;
        String nextTarget = EmbeddedWebAiActivity.normalizeTarget(rawTarget);
        forgetConversationUrl(nextTarget);
        cancel();
        target = nextTarget;
        pageReady = false;
        clearBaseline();
        lastCandidate = "";
        lastDomDebug = "";
        try { webView.stopLoading(); } catch (Throwable ignored) {}
        status("正在为 " + label() + " 新建 FloatLens 专用对话…");
        diag("NEW_CHAT", "url=" + safePath(targetUrl(nextTarget)));
        webView.loadUrl(targetUrl(nextTarget));
    }

    public void destroy() {
        destroyed = true;
        rememberConversationUrl(target, webView.getUrl());
        cancel();
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
        try {
            host.removeView(webView);
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (Throwable ignored) {}
        diag("DESTROY", "engine destroyed");
    }

    public void send(String rawTarget, String rawPrompt) {
        if (destroyed) return;
        String nextTarget = EmbeddedWebAiActivity.normalizeTarget(rawTarget);
        String prompt = clean(rawPrompt);
        if (prompt.isEmpty()) {
            error(nextTarget, "没有可发送的文字");
            return;
        }

        rememberConversationUrl(target, webView.getUrl());
        cancel();
        sending = true;
        target = nextTarget;
        pendingPrompt = prompt;
        startedAt = SystemClock.uptimeMillis();
        composerAttempts = 0;
        sendAttempts = 0;
        emptyPolls = 0;
        clearBaseline();
        lastCandidate = "";
        lastDomDebug = "";
        lastLoggedDebug = "";
        lastDebugLogAt = 0L;
        candidateStableSince = 0L;

        String current = webView.getUrl();
        diag("SEND_BEGIN", "promptLen=" + prompt.length() + " current=" + safePath(current));
        if (!pageReady || !sameTarget(current, target)) {
            stage = Stage.LOADING;
            String resume = savedConversationUrl(target);
            if (!resume.isEmpty()) {
                status("正在恢复 " + label() + " 的 FloatLens 专用对话…");
                diag("LOAD", "resume=" + safePath(resume));
                webView.loadUrl(resume);
            } else {
                status("正在后台打开 " + label() + "…");
                String home = targetUrl(target);
                diag("LOAD", "home=" + safePath(home));
                webView.loadUrl(home);
            }
        } else {
            stage = Stage.PREPARING;
            status("正在继续 " + label() + " 的当前网页对话…");
            captureBaseline();
        }
    }

    private void captureBaseline() {
        if (!active(Stage.PREPARING)) return;
        evaluate(snapshotScript(profile()), raw -> {
            if (!active(Stage.PREPARING)) return;
            DomSnapshot snapshot = parseSnapshot(raw);
            baselinePrimary.clear();
            baselinePrimary.addAll(snapshot.primaryTexts);
            baselineFallback.clear();
            baselineFallback.addAll(snapshot.fallbackTexts);
            baselinePrimaryCount = snapshot.primaryTexts.size();
            baselineFallbackCount = snapshot.fallbackTexts.size();
            lastDomDebug = snapshot.debug;
            diag("BASELINE", snapshot.debug);
            tryComposer();
        });
    }

    private void tryComposer() {
        if (!active(Stage.PREPARING)) return;
        if (timedOut()) {
            fail("等待网页输入框超时" + diagnosticSuffix());
            return;
        }
        composerAttempts++;
        evaluate(fillScript(profile(), pendingPrompt), raw -> {
            if (!active(Stage.PREPARING)) return;
            String value = decodeJsString(raw);
            diag("FILL", "attempt=" + composerAttempts + " result=" + compactDebug(value));
            if (value.startsWith("filled")) {
                stage = Stage.SENDING;
                status("已在后台网页填入问题，正在发送…");
                sendAttempts = 0;
                main.postDelayed(this::trySend, 420L);
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
            status("等待 " + label() + " 登录/输入框…");
            main.postDelayed(this::tryComposer, 700L);
        });
    }

    private void trySend() {
        if (!active(Stage.SENDING)) return;
        if (timedOut()) {
            fail("等待网页发送超时" + diagnosticSuffix());
            return;
        }
        sendAttempts++;
        evaluate(sendScript(profile()), raw -> {
            if (!active(Stage.SENDING)) return;
            String value = decodeJsString(raw);
            diag("SEND", "attempt=" + sendAttempts + " result=" + compactDebug(value));
            if (value.startsWith("sent")) {
                stage = Stage.WAITING;
                status("已发送，正在后台读取网页回答…");
                emptyPolls = 0;
                main.postDelayed(this::pollAnswer, 900L);
                return;
            }
            if (value.startsWith("error")) {
                fail("网页发送失败: " + value);
                return;
            }
            lastDomDebug = value;
            if (sendAttempts < MAX_SEND_ATTEMPTS) {
                status(label() + " 输入已就绪，等待发送按钮…");
                main.postDelayed(this::trySend, 450L);
            } else {
                needsLogin("文字已经填入，但没有可靠找到发送按钮。请打开一次网页登录页面确认登录状态。" + diagnosticSuffix());
            }
        });
    }

    private void pollAnswer() {
        if (!active(Stage.WAITING)) return;
        if (timedOut()) {
            fail("等待网页回答超时" + diagnosticSuffix());
            return;
        }
        evaluate(snapshotScript(profile()), raw -> {
            if (!active(Stage.WAITING)) return;
            DomSnapshot snapshot = parseSnapshot(raw);
            lastDomDebug = snapshot.debug;
            maybeLogSnapshot(snapshot);
            String candidate = chooseNewAnswer(snapshot);
            long now = SystemClock.uptimeMillis();
            rememberConversationUrl(target, webView.getUrl());

            if (candidate.isEmpty()) {
                emptyPolls++;
                if (snapshot.generating) {
                    status(label() + " 正在生成回答…");
                } else if (snapshot.primaryCount > baselinePrimaryCount
                        || snapshot.fallbackCount > baselineFallbackCount) {
                    status(label() + " 已有新的回答节点，正在读取内容…");
                } else if (emptyPolls >= 5) {
                    status(label() + " 已发送，但还没有检测到新的回答节点…");
                } else {
                    status("等待 " + label() + " 回答…");
                }
                main.postDelayed(this::pollAnswer, POLL_MS);
                return;
            }

            emptyPolls = 0;
            if (!candidate.equals(lastCandidate)) {
                lastCandidate = candidate;
                candidateStableSince = now;
                status(snapshot.generating ? label() + " 正在生成回答…" : "正在读取网页回答…");
                main.postDelayed(this::pollAnswer, POLL_MS);
                return;
            }

            if (snapshot.generating) {
                status(label() + " 正在生成回答…");
                main.postDelayed(this::pollAnswer, POLL_MS);
                return;
            }

            if (now - candidateStableSince >= profile().stableMs) {
                String answer = candidate;
                String doneTarget = target;
                rememberConversationUrl(doneTarget, webView.getUrl());
                sending = false;
                pendingPrompt = "";
                stage = Stage.IDLE;
                diag("RESULT", "answerLen=" + answer.length() + " url=" + safePath(webView.getUrl()));
                status(EmbeddedWebAiActivity.targetLabel(doneTarget) + " 网页回答已返回到窗口");
                if (listener != null) listener.onResult(doneTarget, answer);
            } else {
                main.postDelayed(this::pollAnswer, POLL_MS);
            }
        });
    }

    private String chooseNewAnswer(DomSnapshot snapshot) {
        String primary = chooseFromList(snapshot.primaryTexts, baselinePrimaryCount, baselinePrimary, false);
        if (!primary.isEmpty()) return primary;
        return chooseFromList(snapshot.fallbackTexts, baselineFallbackCount, baselineFallback, true);
    }

    private String chooseFromList(List<String> values, int baselineCount, Set<String> baseline, boolean fallback) {
        if (values == null || values.isEmpty()) return "";
        if (values.size() > baselineCount) {
            for (int i = values.size() - 1; i >= Math.max(0, baselineCount); i--) {
                String text = sanitizeCandidate(values.get(i), fallback);
                if (!text.isEmpty()) return text;
            }
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            String raw = compact(values.get(i));
            if (raw.isEmpty() || baseline.contains(raw)) continue;
            String text = sanitizeCandidate(raw, fallback);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private String sanitizeCandidate(String raw, boolean fallback) {
        String text = compact(raw);
        String prompt = compact(pendingPrompt);
        if (text.length() < 2 || text.equals(prompt) || looksLikeUiChrome(text)) return "";
        if (fallback && !prompt.isEmpty()) {
            int index = text.lastIndexOf(prompt);
            if (index >= 0) {
                int after = index + prompt.length();
                if (after < text.length()) {
                    String tail = compact(text.substring(after));
                    if (tail.length() >= 2 && !looksLikeUiChrome(tail)) text = tail;
                }
            }
        }
        return text;
    }

    private boolean looksLikeUiChrome(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        if (s.length() > 180) return false;
        return s.equals("send") || s.equals("发送") || s.equals("stop") || s.equals("停止")
                || s.equals("cancel") || s.equals("取消")
                || s.contains("log in") || s.contains("sign up") || s.contains("登录")
                || s.contains("new chat") || s.contains("新对话") || s.contains("regenerate")
                || s.contains("upgrade plan") || s.contains("privacy policy");
    }

    private String fillScript(ProviderProfile p, String prompt) {
        String template = """
                (function(){try{
                  var text=__TEXT__;
                  var sels=__COMPOSERS__;
                  function visible(x){if(!x)return false;var s=getComputedStyle(x),r=x.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>20&&r.height>10;}
                  function valueOf(x){return ((x.value||x.innerText||x.textContent||'')+'').trim();}
                  var candidates=[];
                  for(var i=0;i<sels.length;i++){
                    var list=document.querySelectorAll(sels[i]);
                    for(var j=0;j<list.length;j++){
                      var x=list[j];
                      if(!visible(x)||x.disabled)continue;
                      var meta=((x.getAttribute('id')||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.getAttribute('placeholder')||'')).toLowerCase();
                      if(meta.indexOf('search')>=0||meta.indexOf('url')>=0||meta.indexOf('address')>=0)continue;
                      if(candidates.indexOf(x)<0)candidates.push(x);
                    }
                  }
                  if(!candidates.length)return 'missing:composer=0';
                  candidates.sort(function(a,b){var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return br.bottom-ar.bottom;});
                  var el=candidates[0];el.focus();
                  if(el.isContentEditable){
                    try{var sel=window.getSelection(),range=document.createRange();range.selectNodeContents(el);sel.removeAllRanges();sel.addRange(range);document.execCommand('delete',false,null);}catch(ignore){}
                    var inserted=false;try{inserted=!!document.execCommand('insertText',false,text);}catch(ignore2){}
                    if(!inserted||!valueOf(el)){el.textContent=text;}
                    try{el.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,inputType:'insertText',data:text}));}catch(ignore3){}
                    try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(ignore4){el.dispatchEvent(new Event('input',{bubbles:true}));}
                    el.dispatchEvent(new Event('change',{bubbles:true}));
                  }else{
                    var proto=Object.getPrototypeOf(el),d=Object.getOwnPropertyDescriptor(proto,'value');
                    if(!d||!d.set)d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')||Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
                    if(d&&d.set)d.set.call(el,text);else el.value=text;
                    try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(ignore5){el.dispatchEvent(new Event('input',{bubbles:true}));}
                    el.dispatchEvent(new Event('change',{bubbles:true}));
                  }
                  var current=valueOf(el);
                  return current.length?'filled:'+current.length:'empty-after-fill';
                }catch(e){return 'error:'+String(e);}})();
                """;
        return template
                .replace("__TEXT__", JSONObject.quote(prompt))
                .replace("__COMPOSERS__", jsArray(merge(p.composers, COMMON_COMPOSERS)));
    }

    private String sendScript(ProviderProfile p) {
        String template = """
                (function(){try{
                  var composerSels=__COMPOSERS__;
                  var sendSels=__SENDERS__;
                  function visible(x){if(!x)return false;var s=getComputedStyle(x),r=x.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>8&&r.height>8;}
                  function usable(x){return visible(x)&&!x.disabled&&x.getAttribute('aria-disabled')!=='true';}
                  var composer=null;
                  for(var i=0;i<composerSels.length&&!composer;i++){
                    var cl=document.querySelectorAll(composerSels[i]);
                    for(var j=cl.length-1;j>=0;j--){if(visible(cl[j])){composer=cl[j];break;}}
                  }
                  var root=(composer&&composer.closest)?(composer.closest('form')||document.body):document.body;
                  var buttons=[];
                  for(var a=0;a<sendSels.length;a++){
                    var list=root.querySelectorAll(sendSels[a]);
                    for(var b=0;b<list.length;b++){if(usable(list[b])&&buttons.indexOf(list[b])<0)buttons.push(list[b]);}
                  }
                  if(!buttons.length){
                    var all=root.querySelectorAll('button,[role="button"]');
                    for(var c=0;c<all.length;c++){
                      var x=all[c],meta=((x.getAttribute('aria-label')||'')+' '+(x.getAttribute('title')||'')+' '+(x.getAttribute('data-testid')||'')+' '+(x.innerText||x.textContent||'')).toLowerCase();
                      if(usable(x)&&(/(^|\\s)(send|submit)(\\s|$)/i.test(meta)||meta.indexOf('发送')>=0)){buttons.push(x);}
                    }
                  }
                  if(buttons.length){
                    buttons.sort(function(a,b){var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return br.bottom-ar.bottom;});
                    var btn=buttons[0];var desc=((btn.getAttribute('data-testid')||'')+' '+(btn.getAttribute('aria-label')||'')+' '+(btn.id||'')).trim();btn.click();return 'sent-click:'+desc;
                  }
                  if(composer){
                    var form=composer.closest?composer.closest('form'):null;
                    if(form&&form.requestSubmit){form.requestSubmit();return 'sent-form';}
                    composer.focus();
                    composer.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
                    composer.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
                    return 'sent-key';
                  }
                  return 'nosend:composer=0,buttons=0';
                }catch(e){return 'error:'+String(e);}})();
                """;
        return template
                .replace("__COMPOSERS__", jsArray(merge(p.composers, COMMON_COMPOSERS)))
                .replace("__SENDERS__", jsArray(merge(p.senders, COMMON_SENDERS)));
    }

    private String snapshotScript(ProviderProfile p) {
        String template = """
                (function(){try{
                  var primarySels=__ANSWERS__;
                  var fallbackSels=__FALLBACKS__;
                  var ignoreSels=__IGNORES__;
                  var composerSels=__COMPOSERS__;
                  var sendSels=__SENDERS__;
                  function visible(x){if(!x)return false;var s=getComputedStyle(x),r=x.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}
                  function clean(t){return ((t||'')+'').replace(/\\u00a0/g,' ').replace(/[ \\t]+\\n/g,'\\n').replace(/\\n{3,}/g,'\\n\\n').trim();}
                  function ignored(x){
                    for(var i=0;i<ignoreSels.length;i++){
                      try{if(x.matches&&x.matches(ignoreSels[i]))return true;if(x.closest&&x.closest(ignoreSels[i]))return true;}catch(ignore){}
                    }
                    var user=x.closest?x.closest('[data-message-author-role="user"],[data-author="user"],[data-testid*=\"user\"]'):null;
                    return !!user;
                  }
                  var composers=[];
                  for(var ci=0;ci<composerSels.length;ci++){
                    var cList=document.querySelectorAll(composerSels[ci]);
                    for(var cj=0;cj<cList.length;cj++){if(composers.indexOf(cList[cj])<0)composers.push(cList[cj]);}
                  }
                  function touchesComposer(x){for(var i=0;i<composers.length;i++){var c=composers[i];if(c===x||c.contains(x)||x.contains(c))return true;}return false;}
                  function collect(sels, strict){
                    var nodes=[];
                    for(var i=0;i<sels.length;i++){
                      var list=document.querySelectorAll(sels[i]);
                      for(var j=0;j<list.length;j++){var x=list[j];if(nodes.indexOf(x)<0)nodes.push(x);}
                    }
                    nodes.sort(function(a,b){if(a===b)return 0;var p=a.compareDocumentPosition(b);return (p&Node.DOCUMENT_POSITION_FOLLOWING)?-1:1;});
                    var out=[];
                    for(var k=0;k<nodes.length;k++){
                      var n=nodes[k];if(!visible(n)||ignored(n)||touchesComposer(n))continue;
                      var meta=((n.getAttribute('data-message-author-role')||'')+' '+(n.getAttribute('data-author')||'')+' '+(n.getAttribute('data-testid')||'')+' '+(n.getAttribute('aria-label')||'')+' '+(n.className||'')).toLowerCase();
                      if(meta.indexOf('user')>=0&&meta.indexOf('assistant')<0)continue;
                      if(!strict){
                        var tag=(n.tagName||'').toLowerCase();
                        if((tag==='article'||meta.indexOf('message')>=0||meta.indexOf('response')>=0||meta.indexOf('markdown')>=0||meta.indexOf('prose')>=0)===false)continue;
                      }
                      var t=clean(n.innerText||n.textContent||'');if(t.length<2||t.length>30000)continue;
                      if(out.length&&out[out.length-1]===t)continue;
                      out.push(t);
                    }
                    return out;
                  }
                  var primary=collect(primarySels,true);
                  var fallback=collect(fallbackSels,false);
                  var generating=false;
                  var controls=document.querySelectorAll('button,[role="button"],gem-icon-button');
                  for(var i=0;i<controls.length;i++){
                    var x=controls[i];if(!visible(x)||x.disabled||x.getAttribute('aria-disabled')==='true')continue;
                    var meta=((x.getAttribute('data-testid')||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.getAttribute('title')||'')+' '+(x.innerText||x.textContent||'')+' '+(x.className||'')).toLowerCase();
                    if(meta.indexOf('stop generating')>=0||meta.indexOf('stop response')>=0||meta.indexOf('stop')>=0||meta.indexOf('abort')>=0||meta.indexOf('停止')>=0){generating=true;break;}
                  }
                  var sendReady=false;
                  for(var si=0;si<sendSels.length&&!sendReady;si++){
                    var sl=document.querySelectorAll(sendSels[si]);
                    for(var sj=0;sj<sl.length;sj++){var sb=sl[sj];if(visible(sb)&&!sb.disabled&&sb.getAttribute('aria-disabled')!=='true'){sendReady=true;break;}}
                  }
                  var composerReady=false;
                  for(var qi=0;qi<composers.length;qi++){if(visible(composers[qi])&&!composers[qi].disabled){composerReady=true;break;}}
                  return JSON.stringify({primary:primary.slice(-40),fallback:fallback.slice(-60),generating:generating,sendReady:sendReady,composerReady:composerReady,primaryCount:primary.length,fallbackCount:fallback.length,debug:'provider=__PROVIDER__ primary='+primary.length+',fallback='+fallback.length+',generating='+generating+',sendReady='+sendReady+',composerReady='+composerReady+',url='+location.pathname});
                }catch(e){return JSON.stringify({primary:[],fallback:[],generating:false,sendReady:false,composerReady:false,primaryCount:0,fallbackCount:0,debug:'snapshot error:'+String(e)});}})();
                """;
        return template
                .replace("__ANSWERS__", jsArray(p.answers))
                .replace("__FALLBACKS__", jsArray(merge(p.answers, COMMON_ANSWERS,
                        new String[]{"main article", "main [class*=\"message\"]", "main [class*=\"response\"]"})))
                .replace("__IGNORES__", jsArray(merge(p.ignores, COMMON_IGNORES)))
                .replace("__COMPOSERS__", jsArray(merge(p.composers, COMMON_COMPOSERS)))
                .replace("__SENDERS__", jsArray(merge(p.senders, COMMON_SENDERS)))
                .replace("__PROVIDER__", p.id);
    }

    private DomSnapshot parseSnapshot(String raw) {
        DomSnapshot snapshot = new DomSnapshot();
        try {
            String json = decodeJsString(raw);
            Object parsed = new JSONTokener(json).nextValue();
            if (!(parsed instanceof JSONObject)) {
                snapshot.debug = "parse: non-object";
                return snapshot;
            }
            JSONObject o = (JSONObject) parsed;
            readTexts(o.optJSONArray("primary"), snapshot.primaryTexts);
            readTexts(o.optJSONArray("fallback"), snapshot.fallbackTexts);
            snapshot.generating = o.optBoolean("generating", false);
            snapshot.sendReady = o.optBoolean("sendReady", false);
            snapshot.composerReady = o.optBoolean("composerReady", false);
            snapshot.primaryCount = o.optInt("primaryCount", snapshot.primaryTexts.size());
            snapshot.fallbackCount = o.optInt("fallbackCount", snapshot.fallbackTexts.size());
            snapshot.debug = o.optString("debug", "");
        } catch (Throwable t) {
            snapshot.debug = "parse error: " + messageOf(t);
        }
        return snapshot;
    }

    private static void readTexts(JSONArray array, List<String> out) {
        if (array == null || out == null) return;
        for (int i = 0; i < array.length(); i++) {
            String value = compact(array.optString(i, ""));
            if (!value.isEmpty()) out.add(value);
        }
    }

    private void evaluate(String js, android.webkit.ValueCallback<String> callback) {
        if (destroyed) return;
        try {
            webView.evaluateJavascript(js, callback);
        } catch (Throwable t) {
            fail("网页脚本执行失败: " + messageOf(t));
        }
    }

    private boolean active(Stage expected) {
        return !destroyed && sending && !pendingPrompt.isEmpty() && stage == expected;
    }

    private boolean timedOut() {
        return SystemClock.uptimeMillis() - startedAt > TIMEOUT_MS;
    }

    private ProviderProfile profile() {
        String id = EmbeddedWebAiActivity.normalizeTarget(target);
        if (BrowserAiBridge.TARGET_GEMINI.equals(id)) return GEMINI;
        if (BrowserAiBridge.TARGET_DEEPSEEK.equals(id)) return DEEPSEEK;
        if (BrowserAiBridge.TARGET_CLAUDE.equals(id)) return CLAUDE;
        if (BrowserAiBridge.TARGET_GROK.equals(id)) return GROK;
        return CHATGPT;
    }

    private String label() {
        return EmbeddedWebAiActivity.targetLabel(target);
    }

    private String diagnosticSuffix() {
        return lastDomDebug == null || lastDomDebug.isBlank() ? "" : "（" + lastDomDebug + "）";
    }

    private void needsLogin(String message) {
        if (!sending) return;
        String failedTarget = target;
        sending = false;
        stage = Stage.IDLE;
        diag("NEEDS_LOGIN", compactDebug(message));
        status("需要处理网页登录");
        if (listener != null) listener.onNeedsLogin(failedTarget, message);
    }

    private void fail(String message) {
        if (!sending) return;
        String failedTarget = target;
        sending = false;
        stage = Stage.IDLE;
        diag("FAIL", compactDebug(message));
        status("网页 AI 失败");
        if (listener != null) listener.onError(failedTarget, message);
    }

    private void error(String failedTarget, String message) {
        diag("ERROR", compactDebug(message));
        if (listener != null) listener.onError(failedTarget, message);
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }

    private void maybeLogSnapshot(DomSnapshot snapshot) {
        long now = SystemClock.uptimeMillis();
        String debug = snapshot == null ? "" : snapshot.debug;
        if (!debug.equals(lastLoggedDebug) || now - lastDebugLogAt >= DEBUG_LOG_INTERVAL_MS) {
            lastLoggedDebug = debug;
            lastDebugLogAt = now;
            diag("POLL", debug);
        }
    }

    private void diag(String event, String message) {
        DiagnosticLog.i(context, "WEB_AI",
                event + " target=" + EmbeddedWebAiActivity.normalizeTarget(target)
                        + " stage=" + stage + " " + (message == null ? "" : message));
    }

    private void clearBaseline() {
        baselinePrimary.clear();
        baselineFallback.clear();
        baselinePrimaryCount = 0;
        baselineFallbackCount = 0;
    }

    private SharedPreferences sessionPrefs() {
        return context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
    }

    private String sessionKey(String rawTarget) {
        return SESSION_PREFIX + EmbeddedWebAiActivity.normalizeTarget(rawTarget);
    }

    private String savedConversationUrl(String rawTarget) {
        String saved = clean(sessionPrefs().getString(sessionKey(rawTarget), ""));
        return isReusableConversationUrl(rawTarget, saved) ? saved : "";
    }

    private void forgetConversationUrl(String rawTarget) {
        sessionPrefs().edit().remove(sessionKey(rawTarget)).apply();
    }

    private void rememberConversationUrl(String rawTarget, String rawUrl) {
        String url = clean(rawUrl);
        if (!isReusableConversationUrl(rawTarget, url)) return;
        sessionPrefs().edit().putString(sessionKey(rawTarget), url).apply();
    }

    private boolean isReusableConversationUrl(String rawTarget, String url) {
        if (url == null || url.isBlank() || !sameTarget(url, rawTarget)) return false;
        android.net.Uri uri;
        try { uri = android.net.Uri.parse(url); }
        catch (Throwable t) { return false; }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("login") || lower.contains("signin") || lower.contains("sign-in")
                || lower.contains("signup") || lower.contains("sign-up") || lower.contains("oauth")
                || lower.contains("auth")) return false;

        switch (EmbeddedWebAiActivity.normalizeTarget(rawTarget)) {
            case BrowserAiBridge.TARGET_CHATGPT:
                return lower.contains("/c/");
            case BrowserAiBridge.TARGET_GEMINI:
                return lower.startsWith("/app/") && lower.length() > 5;
            case BrowserAiBridge.TARGET_CLAUDE:
                return !lower.equals("/") && !lower.equals("/new") && !lower.equals("/new/");
            case BrowserAiBridge.TARGET_GROK:
                return !lower.equals("/") && lower.length() > 1;
            case BrowserAiBridge.TARGET_DEEPSEEK:
                return !lower.equals("/") && lower.length() > 1;
            default:
                return false;
        }
    }

    private boolean sameTarget(String url, String rawTarget) {
        if (url == null || url.isEmpty()) return false;
        String hostName;
        try { hostName = android.net.Uri.parse(url).getHost(); }
        catch (Throwable t) { hostName = null; }
        if (hostName == null) return false;
        hostName = hostName.toLowerCase(Locale.ROOT);
        switch (EmbeddedWebAiActivity.normalizeTarget(rawTarget)) {
            case BrowserAiBridge.TARGET_GEMINI:
                return hostName.contains("gemini.google.com");
            case BrowserAiBridge.TARGET_CLAUDE:
                return hostName.contains("claude.ai");
            case BrowserAiBridge.TARGET_GROK:
                return hostName.contains("grok.com") || hostName.contains("x.com");
            case BrowserAiBridge.TARGET_DEEPSEEK:
                return hostName.contains("chat.deepseek.com") || hostName.contains("deepseek.com");
            default:
                return hostName.contains("chatgpt.com") || hostName.contains("chat.openai.com");
        }
    }

    private String targetUrl(String rawTarget) {
        switch (EmbeddedWebAiActivity.normalizeTarget(rawTarget)) {
            case BrowserAiBridge.TARGET_GEMINI:
                return "https://gemini.google.com/app";
            case BrowserAiBridge.TARGET_CLAUDE:
                return "https://claude.ai/new";
            case BrowserAiBridge.TARGET_GROK:
                return "https://grok.com/";
            case BrowserAiBridge.TARGET_DEEPSEEK:
                return "https://chat.deepseek.com/";
            default:
                return "https://chatgpt.com/";
        }
    }

    private static String jsArray(String[] values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) array.put(value);
            }
        }
        return array.toString();
    }

    private static String[] merge(String[]... groups) {
        ArrayList<String> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (groups != null) {
            for (String[] group : groups) {
                if (group == null) continue;
                for (String value : group) {
                    if (value == null || value.isBlank() || !seen.add(value)) continue;
                    out.add(value);
                }
            }
        }
        return out.toArray(new String[0]);
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

    private static String compactDebug(String text) {
        String value = clean(text).replace('\n', ' ').replace('\r', ' ');
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String safePath(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        try {
            android.net.Uri uri = android.net.Uri.parse(rawUrl);
            String host = uri.getHost() == null ? "" : uri.getHost();
            String path = uri.getPath() == null ? "/" : uri.getPath();
            return host + path;
        } catch (Throwable t) {
            return "invalid-url";
        }
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
