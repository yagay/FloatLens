package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Experimental no-API/no-server bridge that drives the user's already signed-in AI web page
 * through Android accessibility. It never reads browser cookies or passwords. The bridge only
 * finds the visible chat composer, inserts the prompt, presses Send and compares visible text
 * before/after sending to recover the newest response.
 */
public final class BrowserAiBridge {
    public static final String TARGET_CHATGPT = "chatgpt";
    public static final String TARGET_GEMINI = "gemini";
    public static final String TARGET_CLAUDE = "claude";
    public static final String TARGET_GROK = "grok";
    public static final String TARGET_DEEPSEEK = "deepseek";

    public static final String[] TARGET_IDS = {
            TARGET_CHATGPT, TARGET_GEMINI, TARGET_CLAUDE, TARGET_GROK, TARGET_DEEPSEEK
    };
    public static final String[] TARGET_LABELS = {
            "ChatGPT", "Gemini", "Claude", "Grok", "DeepSeek"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long OPEN_DELAY_MS = 900L;
    private static final long RESPONSE_MIN_WAIT_MS = 1_200L;
    private static final long RESPONSE_STABLE_MS = 1_800L;
    private static final long TIMEOUT_MS = 90_000L;
    private static final int MAX_NODES = 2_800;

    public interface Listener {
        void onBrowserAiStatus(String message);
        void onBrowserAiResult(String target, String targetLabel, String text, String error);
    }

    private enum Stage { OPENING, WAITING_RESPONSE }

    private static final class Pending {
        final String target;
        final String prompt;
        final long startedAt;
        Stage stage = Stage.OPENING;
        Set<String> beforeTexts = new HashSet<>();
        String lastCandidate = "";
        long candidateStableSince;
        long sentAt;
        long lastProcessAt;
        boolean finished;

        Pending(String target, String prompt) {
            this.target = normalizeTarget(target);
            this.prompt = prompt == null ? "" : prompt.trim();
            this.startedAt = SystemClock.uptimeMillis();
        }
    }

    private static volatile Pending pending;
    private static volatile WeakReference<Listener> listenerRef = new WeakReference<>(null);

    public static void setListener(Listener listener) {
        listenerRef = new WeakReference<>(listener);
    }

    public static void clearListener(Listener listener) {
        Listener current = listenerRef.get();
        if (current == listener) listenerRef = new WeakReference<>(null);
    }

    public static boolean isRunning() {
        Pending p = pending;
        return p != null && !p.finished;
    }

    public static void cancel() {
        Pending p = pending;
        if (p != null) p.finished = true;
        pending = null;
        postStatus("网页 AI 已取消");
    }

    public static void start(Context context, String target, String prompt) throws IOException {
        if (context == null) throw new IOException("无效的应用上下文");
        if (!LensAccessibilityService.ready()) {
            throw new IOException("请先开启 FloatLens 无障碍服务");
        }
        String clean = prompt == null ? "" : prompt.trim();
        if (clean.isEmpty()) throw new IOException("没有可发送的文字");

        Pending previous = pending;
        if (previous != null) previous.finished = true;
        Pending next = new Pending(target, clean);
        pending = next;
        postStatus("正在打开 " + targetLabel(next.target) + "…");

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl(next.target)));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Prefer Chrome because this mode intentionally reuses the user's browser login session.
        // Fall back to the default web handler when Chrome is unavailable.
        try {
            context.getPackageManager().getPackageInfo("com.android.chrome", 0);
            intent.setPackage("com.android.chrome");
        } catch (Throwable ignored) {}
        try {
            context.startActivity(intent);
        } catch (Throwable first) {
            try {
                intent.setPackage(null);
                context.startActivity(intent);
            } catch (Throwable second) {
                pending = null;
                throw new IOException("无法打开浏览器: " + messageOf(second), second);
            }
        }
        MAIN.postDelayed(() -> process(LensAccessibilityService.get()), OPEN_DELAY_MS);
    }

    public static void onAccessibilityEvent(LensAccessibilityService service, AccessibilityEvent event) {
        Pending p = pending;
        if (p == null || p.finished || service == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - p.lastProcessAt < 280L) return;
        p.lastProcessAt = now;
        MAIN.postDelayed(() -> process(service), 120L);
    }

    private static void process(LensAccessibilityService service) {
        Pending p = pending;
        if (p == null || p.finished || service == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - p.startedAt > TIMEOUT_MS) {
            fail(p, "等待网页回答超时。请确认网页已登录，并保持页面在前台。");
            return;
        }
        AccessibilityNodeInfo root;
        try { root = service.getRootInActiveWindow(); }
        catch (Throwable t) { root = null; }
        if (root == null) return;
        String pkg = safePackage(root);
        if (pkg.equals(service.getPackageName()) || pkg.equals("com.android.systemui")) return;

        if (p.stage == Stage.OPENING) {
            if (now - p.startedAt < OPEN_DELAY_MS) return;
            Composer composer = findComposer(root, service.screenBounds());
            if (composer == null || composer.node == null) {
                postStatus("等待 " + targetLabel(p.target) + " 输入框…请确认网页已登录");
                return;
            }
            p.beforeTexts = new HashSet<>(collectVisibleTexts(root));
            if (!setNodeText(composer.node, p.prompt)) {
                fail(p, "找到输入框，但无法写入文字");
                return;
            }
            postStatus("已填入提示词，正在发送…");
            MAIN.postDelayed(() -> send(service, p), 260L);
            return;
        }

        if (p.stage == Stage.WAITING_RESPONSE) {
            if (now - p.sentAt < RESPONSE_MIN_WAIT_MS) return;
            List<String> current = collectVisibleTexts(root);
            String candidate = extractResponse(p, current);
            if (candidate.isBlank()) {
                postStatus("已发送，等待 " + targetLabel(p.target) + " 回答…");
                return;
            }
            if (!candidate.equals(p.lastCandidate)) {
                p.lastCandidate = candidate;
                p.candidateStableSince = now;
                postStatus("正在读取 " + targetLabel(p.target) + " 回答…");
                return;
            }
            if (now - p.candidateStableSince >= RESPONSE_STABLE_MS) {
                complete(p, candidate);
            }
        }
    }

    private static void send(LensAccessibilityService service, Pending p) {
        if (pending != p || p.finished || service == null) return;
        AccessibilityNodeInfo root;
        try { root = service.getRootInActiveWindow(); }
        catch (Throwable t) { root = null; }
        if (root == null) return;
        Rect screen = service.screenBounds();
        AccessibilityNodeInfo send = findSendButton(root, screen);
        boolean sent = false;
        if (send != null) {
            try { sent = send.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
            catch (Throwable ignored) {}
        }
        if (!sent) {
            Composer composer = findComposer(root, screen);
            if (composer != null && composer.node != null) {
                try {
                    sent = composer.node.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                } catch (Throwable ignored) {}
            }
        }
        if (!sent) {
            fail(p, "已填入文字，但没有找到发送按钮。可以手动点击发送后再重试。");
            return;
        }
        p.stage = Stage.WAITING_RESPONSE;
        p.sentAt = SystemClock.uptimeMillis();
        p.beforeTexts.add(p.prompt);
        postStatus("已发送到 " + targetLabel(p.target) + "，等待回答…");
        MAIN.postDelayed(() -> process(service), RESPONSE_MIN_WAIT_MS);
    }

    private static final class Composer {
        AccessibilityNodeInfo node;
        int score;
    }

    private static Composer findComposer(AccessibilityNodeInfo root, Rect screen) {
        Composer best = new Composer();
        best.score = Integer.MIN_VALUE;
        scoreComposer(root, screen, best, new int[]{0}, 0);
        return best.score >= 75 ? best : null;
    }

    private static void scoreComposer(AccessibilityNodeInfo node, Rect screen, Composer best,
                                      int[] count, int depth) {
        if (node == null || count[0]++ > MAX_NODES || depth > 70) return;
        try {
            if (node.isVisibleToUser()) {
                boolean editable = node.isEditable() || supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT);
                String cls = safe(node.getClassName()).toLowerCase(Locale.ROOT);
                if (editable || cls.contains("edittext")) {
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    String id = safe(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                    String semantic = (safe(node.getHintText()) + " " + safe(node.getContentDescription())
                            + " " + safe(node.getText())).toLowerCase(Locale.ROOT);
                    int score = 35;
                    if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 20;
                    if (cls.contains("edittext")) score += 12;
                    if (containsAny(semantic, "message", "prompt", "ask anything", "ask ",
                            "chat", "send a message", "type a message", "enter a prompt",
                            "输入", "消息", "提问", "询问", "问点什么", "向 deepseek")) score += 90;
                    if (id.contains("url_bar") || id.contains("omnibox") || id.contains("address")) score -= 500;
                    if (screen != null && !screen.isEmpty() && !r.isEmpty()) {
                        if (r.centerY() > screen.height() * 0.45f) score += 25;
                        if (r.bottom > screen.height() * 0.68f) score += 20;
                        if (r.top < screen.height() * 0.16f) score -= 80;
                    }
                    if (score > best.score) {
                        best.score = score;
                        best.node = node;
                    }
                }
            }
        } catch (Throwable ignored) {}
        int children = 0;
        try { children = Math.min(node.getChildCount(), 220); } catch (Throwable ignored) {}
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) scoreComposer(child, screen, best, count, depth + 1);
        }
    }

    private static AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, Rect screen) {
        NodeScore best = new NodeScore();
        best.score = Integer.MIN_VALUE;
        scoreSend(root, screen, best, new int[]{0}, 0);
        return best.score >= 70 ? best.node : null;
    }

    private static final class NodeScore {
        AccessibilityNodeInfo node;
        int score;
    }

    private static void scoreSend(AccessibilityNodeInfo node, Rect screen, NodeScore best,
                                  int[] count, int depth) {
        if (node == null || count[0]++ > MAX_NODES || depth > 70) return;
        try {
            if (node.isVisibleToUser()) {
                boolean clickable = node.isClickable() || supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK);
                if (clickable) {
                    String semantic = (safe(node.getText()) + " " + safe(node.getContentDescription())
                            + " " + safe(node.getHintText())).trim().toLowerCase(Locale.ROOT);
                    String id = safe(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    int score = 0;
                    if (containsAny(semantic, "send", "submit", "发送", "发送消息", "send message",
                            "send prompt", "提交", "arrow up")) score += 110;
                    if (id.contains("send") || id.contains("submit")) score += 80;
                    if (screen != null && !screen.isEmpty() && !r.isEmpty()) {
                        if (r.centerY() > screen.height() * 0.45f) score += 25;
                        if (r.bottom > screen.height() * 0.65f) score += 20;
                        if (r.top < screen.height() * 0.16f) score -= 60;
                    }
                    if (score > best.score) {
                        best.score = score;
                        best.node = node;
                    }
                }
            }
        } catch (Throwable ignored) {}
        int children = 0;
        try { children = Math.min(node.getChildCount(), 220); } catch (Throwable ignored) {}
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) scoreSend(child, screen, best, count, depth + 1);
        }
    }

    private static boolean setNodeText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        try { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS); } catch (Throwable ignored) {}
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        try { return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args); }
        catch (Throwable t) { return false; }
    }

    private static List<String> collectVisibleTexts(AccessibilityNodeInfo root) {
        ArrayList<String> out = new ArrayList<>();
        collectTexts(root, out, new int[]{0}, 0);
        return out;
    }

    private static void collectTexts(AccessibilityNodeInfo node, List<String> out, int[] count, int depth) {
        if (node == null || count[0]++ > MAX_NODES || depth > 75) return;
        try {
            if (node.isVisibleToUser()) {
                addText(out, node.getText());
                addText(out, node.getContentDescription());
            }
        } catch (Throwable ignored) {}
        int children = 0;
        try { children = Math.min(node.getChildCount(), 240); } catch (Throwable ignored) {}
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectTexts(child, out, count, depth + 1);
        }
    }

    private static void addText(List<String> out, CharSequence value) {
        if (value == null) return;
        String clean = value.toString().trim().replaceAll("[\\t ]+", " ");
        if (clean.isEmpty() || clean.length() > 20_000) return;
        out.add(clean);
    }

    private static String extractResponse(Pending p, List<String> current) {
        if (current == null || current.isEmpty()) return "";
        int promptIndex = -1;
        for (int i = 0; i < current.size(); i++) {
            String s = current.get(i);
            if (samePrompt(s, p.prompt)) promptIndex = i;
        }

        LinkedHashSet<String> pieces = new LinkedHashSet<>();
        int start = promptIndex >= 0 ? promptIndex + 1 : 0;
        for (int i = start; i < current.size(); i++) {
            String s = current.get(i).trim();
            if (s.length() < 2 || samePrompt(s, p.prompt)) continue;
            if (p.beforeTexts.contains(s)) continue;
            if (isUiNoise(s)) continue;
            pieces.add(s);
        }
        if (pieces.isEmpty() && promptIndex < 0) {
            for (String s : current) {
                if (s == null || s.length() < 2 || p.beforeTexts.contains(s) || isUiNoise(s)) continue;
                pieces.add(s);
            }
        }
        if (pieces.isEmpty()) return "";

        // Prefer substantial answer blocks over tiny labels introduced during generation.
        ArrayList<String> substantial = new ArrayList<>();
        for (String s : pieces) if (s.length() >= 12 || pieces.size() == 1) substantial.add(s);
        if (substantial.isEmpty()) substantial.addAll(pieces);
        StringBuilder out = new StringBuilder();
        int from = Math.max(0, substantial.size() - 18);
        for (int i = from; i < substantial.size(); i++) {
            String s = substantial.get(i);
            if (out.indexOf(s) >= 0) continue;
            if (out.length() > 0) out.append('\n');
            out.append(s);
            if (out.length() > 16_000) break;
        }
        return out.toString().trim();
    }

    private static boolean samePrompt(String visible, String prompt) {
        if (visible == null || prompt == null) return false;
        String a = visible.trim().replaceAll("\\s+", " ");
        String b = prompt.trim().replaceAll("\\s+", " ");
        if (a.equals(b)) return true;
        if (b.length() > 80 && a.contains(b.substring(0, Math.min(120, b.length())))) return true;
        return false;
    }

    private static boolean isUiNoise(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return true;
        return s.equals("send") || s.equals("发送") || s.equals("submit") || s.equals("copy")
                || s.equals("复制") || s.equals("share") || s.equals("分享") || s.equals("retry")
                || s.equals("regenerate") || s.equals("stop generating") || s.equals("停止生成")
                || s.equals("like") || s.equals("dislike") || s.equals("new chat") || s.equals("新对话")
                || s.equals("search") || s.equals("搜索") || s.equals("tools") || s.equals("工具")
                || s.equals("chatgpt") || s.equals("gemini") || s.equals("claude") || s.equals("grok")
                || s.equals("deepseek") || s.equals("menu") || s.equals("菜单");
    }

    private static boolean supportsAction(AccessibilityNodeInfo node, int actionId) {
        if (node == null) return false;
        try {
            List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
            if (actions != null) for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
                if (action != null && action.getId() == actionId) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static void complete(Pending p, String text) {
        if (p == null || p.finished || pending != p) return;
        p.finished = true;
        pending = null;
        deliver(p.target, text, "");
    }

    private static void fail(Pending p, String error) {
        if (p == null || p.finished || pending != p) return;
        p.finished = true;
        pending = null;
        deliver(p.target, "", error == null ? "网页 AI 操作失败" : error);
    }

    private static void deliver(String target, String text, String error) {
        MAIN.post(() -> {
            Listener listener = listenerRef.get();
            if (listener != null) {
                listener.onBrowserAiResult(target, targetLabel(target), text == null ? "" : text,
                        error == null ? "" : error);
                return;
            }
            LensAccessibilityService service = LensAccessibilityService.get();
            if (service == null) return;
            Intent intent = new Intent(service, AiAssistantActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(AiAssistantActivity.EXTRA_BROWSER_TARGET, targetLabel(target))
                    .putExtra(AiAssistantActivity.EXTRA_BROWSER_RESULT, text == null ? "" : text)
                    .putExtra(AiAssistantActivity.EXTRA_BROWSER_ERROR, error == null ? "" : error);
            service.startActivity(intent);
        });
    }

    private static void postStatus(String status) {
        MAIN.post(() -> {
            Listener listener = listenerRef.get();
            if (listener != null) listener.onBrowserAiStatus(status);
        });
    }

    public static String targetLabel(String target) {
        return switch (normalizeTarget(target)) {
            case TARGET_GEMINI -> "Gemini";
            case TARGET_CLAUDE -> "Claude";
            case TARGET_GROK -> "Grok";
            case TARGET_DEEPSEEK -> "DeepSeek";
            default -> "ChatGPT";
        };
    }

    public static String targetUrl(String target) {
        return switch (normalizeTarget(target)) {
            case TARGET_GEMINI -> "https://gemini.google.com/app";
            case TARGET_CLAUDE -> "https://claude.ai/new";
            case TARGET_GROK -> "https://grok.com/";
            case TARGET_DEEPSEEK -> "https://chat.deepseek.com/";
            default -> "https://chatgpt.com/";
        };
    }

    public static String normalizeTarget(String target) {
        if (TARGET_GEMINI.equals(target)) return TARGET_GEMINI;
        if (TARGET_CLAUDE.equals(target)) return TARGET_CLAUDE;
        if (TARGET_GROK.equals(target)) return TARGET_GROK;
        if (TARGET_DEEPSEEK.equals(target)) return TARGET_DEEPSEEK;
        return TARGET_CHATGPT;
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safePackage(AccessibilityNodeInfo node) {
        try { return node != null && node.getPackageName() != null ? node.getPackageName().toString() : ""; }
        catch (Throwable t) { return ""; }
    }

    private static String messageOf(Throwable t) {
        if (t == null) return "未知错误";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private BrowserAiBridge() {}
}
