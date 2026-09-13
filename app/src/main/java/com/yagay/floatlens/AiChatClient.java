package com.yagay.floatlens;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minimal OpenAI-compatible chat client shared by all FloatLens AI providers. */
public final class AiChatClient {
    private static final int MAX_RESPONSE_CHARS = 4_000_000;

    public static final class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role == null ? "user" : role;
            this.content = content == null ? "" : content;
        }
    }

    public static final class Result {
        public final String text;
        public final String model;

        Result(String text, String model) {
            this.text = text == null ? "" : text;
            this.model = model == null ? "" : model;
        }
    }

    public static Result chat(Context context, List<Message> messages) throws IOException {
        if (context == null) throw new IOException("无效的应用上下文");
        if (!AiConfigStore.isConfigured(context)) throw new IOException("AI 尚未配置");
        if (messages == null || messages.isEmpty()) throw new IOException("没有可发送的消息");

        String endpoint = AiConfigStore.endpoint(context);
        String model = AiConfigStore.model(context);
        String apiKey = AiConfigStore.apiKey(context);
        if (endpoint.isBlank() || model.isBlank()) throw new IOException("AI 接口地址或模型为空");

        JSONObject body = new JSONObject();
        JSONArray messageArray = new JSONArray();
        try {
            body.put("model", model);
            for (Message message : messages) {
                if (message == null || message.content.isBlank()) continue;
                JSONObject one = new JSONObject();
                one.put("role", message.role);
                one.put("content", message.content);
                messageArray.put(one);
            }
            body.put("messages", messageArray);
        } catch (Throwable t) {
            throw new IOException("无法构建 AI 请求", t);
        }

        HttpURLConnection conn = null;
        try {
            conn = open(endpoint, "POST", apiKey);
            conn.setDoOutput(true);
            if (AiConfigStore.PROVIDER_OPENROUTER.equals(AiConfigStore.provider(context))) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/yagay/FloatLens");
                conn.setRequestProperty("X-Title", "FloatLens");
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
                out.flush();
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = stream == null ? "" : readUtf8(stream);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + formatApiError(response));
            }
            return parseResult(response, model);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("AI 请求失败: " + messageOf(t), t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Reads the standard OpenAI-compatible /v1/models endpoint. */
    public static List<String> listModels(Context context, String provider) throws IOException {
        if (context == null) throw new IOException("无效的应用上下文");
        String endpoint = AiConfigStore.modelsEndpoint(context, provider);
        if (endpoint.isBlank()) throw new IOException("模型接口地址为空");
        String apiKey = AiConfigStore.apiKey(context, provider);

        HttpURLConnection conn = null;
        try {
            conn = open(endpoint, "GET", apiKey);
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = stream == null ? "" : readUtf8(stream);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + formatApiError(response));
            }
            JSONObject root = new JSONObject(response);
            JSONArray data = root.optJSONArray("data");
            if (data == null) throw new IOException("模型列表中没有 data");
            ArrayList<String> models = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                if (!id.isEmpty() && !models.contains(id)) models.add(id);
            }
            Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
            return models;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("读取模型列表失败: " + messageOf(t), t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static Result test(Context context) throws IOException {
        ArrayList<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "You are testing an API connection. Reply briefly."));
        messages.add(new Message("user", "Reply with OK."));
        return chat(context, messages);
    }

    private static HttpURLConnection open(String endpoint, String method, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(90_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("User-Agent", "FloatLens/1.0 AI client");
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        return conn;
    }

    private static Result parseResult(String json, String requestedModel) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("AI 返回结果中没有 choices");
        }
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        Object raw = message == null ? null : message.opt("content");
        String text = extractContent(raw).trim();
        if (text.isEmpty()) {
            String refusal = message == null ? "" : message.optString("refusal", "");
            if (!refusal.isBlank()) text = refusal.trim();
        }
        if (text.isEmpty()) throw new IOException("AI 返回了空内容");
        String model = root.optString("model", requestedModel);
        return new Result(text, model);
    }

    private static String extractContent(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return "";
        if (raw instanceof String) return (String) raw;
        if (raw instanceof JSONArray array) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                Object part = array.opt(i);
                if (part instanceof String s) {
                    if (out.length() > 0) out.append('\n');
                    out.append(s);
                } else if (part instanceof JSONObject o) {
                    String text = o.optString("text", "");
                    if (text.isBlank()) text = o.optString("content", "");
                    if (!text.isBlank()) {
                        if (out.length() > 0) out.append('\n');
                        out.append(text);
                    }
                }
            }
            return out.toString();
        }
        return String.valueOf(raw);
    }

    private static String formatApiError(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JSONObject root = new JSONObject(body);
            Object error = root.opt("error");
            if (error instanceof JSONObject o) {
                String message = o.optString("message", "");
                if (!message.isBlank()) return " · " + message;
            } else if (error instanceof String s && !s.isBlank()) {
                return " · " + s;
            }
            String message = root.optString("message", "");
            if (!message.isBlank()) return " · " + message;
        } catch (Throwable ignored) {}
        String compact = body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 300) compact = compact.substring(0, 300) + "…";
        return compact.isEmpty() ? "" : " · " + compact;
    }

    private static String readUtf8(InputStream input) throws IOException {
        StringBuilder out = new StringBuilder(16_384);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) >= 0) {
                if (n == 0) continue;
                if (out.length() + n > MAX_RESPONSE_CHARS) throw new IOException("AI 响应过大");
                out.append(buffer, 0, n);
            }
        }
        return out.toString();
    }

    private static String messageOf(Throwable t) {
        if (t == null) return "未知错误";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private AiChatClient() {}
}
