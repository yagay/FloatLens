package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Online bilingual dictionary backed by Wiktionary's structured definition endpoint. */
public final class OnlineDictionaryClient {
    private static final String PREFS = "floatlens_online_dictionary";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MODE = "mode";

    public static final int MODE_LOCAL_FIRST = 0;
    public static final int MODE_BOTH = 1;
    public static final int MODE_ONLINE_ONLY = 2;

    private static final int MAX_SECTIONS = 8;
    private static final int MAX_DEFINITIONS_PER_SECTION = 5;
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final LruCache<String, Result> CACHE = new LruCache<>(64);

    public static final class Definition {
        public final String text;
        public final String example;

        Definition(String text, String example) {
            this.text = safe(text);
            this.example = safe(example);
        }
    }

    public static final class Section {
        public final String language;
        public final String partOfSpeech;
        public final List<Definition> definitions;

        Section(String language, String partOfSpeech, List<Definition> definitions) {
            this.language = safe(language);
            this.partOfSpeech = safe(partOfSpeech);
            this.definitions = Collections.unmodifiableList(new ArrayList<>(definitions));
        }
    }

    public static final class Result {
        public final String query;
        public final boolean chineseQuery;
        public final String sourceHost;
        public final List<Section> sections;

        Result(String query, boolean chineseQuery, String sourceHost, List<Section> sections) {
            this.query = safe(query);
            this.chineseQuery = chineseQuery;
            this.sourceHost = safe(sourceHost);
            this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        }

        public boolean isEmpty() { return sections.isEmpty(); }
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int mode(Context context) {
        int value = prefs(context).getInt(KEY_MODE, MODE_LOCAL_FIRST);
        return value >= MODE_LOCAL_FIRST && value <= MODE_ONLINE_ONLY ? value : MODE_LOCAL_FIRST;
    }

    public static void setMode(Context context, int mode) {
        int value = Math.max(MODE_LOCAL_FIRST, Math.min(MODE_ONLINE_ONLY, mode));
        prefs(context).edit().putInt(KEY_MODE, value).apply();
    }

    public static String modeLabel(int mode) {
        return switch (mode) {
            case MODE_BOTH -> "本地 + 在线同时查询";
            case MODE_ONLINE_ONLY -> "仅在线";
            default -> "本地优先，查不到再联网";
        };
    }

    public static boolean isChineseQuery(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    /**
     * English input is queried against Chinese Wiktionary so definitions are Chinese.
     * Chinese input is queried against English Wiktionary so definitions are English.
     */
    public static Result lookup(String rawQuery) throws IOException {
        String query = DictionaryManager.normalizeQuery(rawQuery);
        boolean chinese = isChineseQuery(query);
        if (query.isBlank()) return new Result("", chinese, "", Collections.emptyList());

        String host = chinese ? "en.wiktionary.org" : "zh.wiktionary.org";
        String key = host + "|" + query.toLowerCase(Locale.ROOT);
        Result cached = CACHE.get(key);
        if (cached != null) return cached;

        String encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20");
        String url = "https://" + host + "/api/rest_v1/page/definition/" + encoded;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(12_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent",
                    "FloatLens/1.0 (https://github.com/yagay/FloatLens; Wiktionary lookup)");
            int code = conn.getResponseCode();
            if (code == 404) {
                Result empty = new Result(query, chinese, host, Collections.emptyList());
                CACHE.put(key, empty);
                return empty;
            }
            if (code < 200 || code >= 300) throw new IOException("Wiktionary HTTP " + code);

            String body = readUtf8(conn.getInputStream());
            Result result = parse(query, chinese, host, body);
            CACHE.put(key, result);
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("在线词典解析失败: " + safe(t.getMessage()), t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Result parse(String query, boolean chinese, String host, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        ArrayList<Section> out = new ArrayList<>();

        String preferred = chinese ? "zh" : "en";
        JSONArray preferredArray = root.optJSONArray(preferred);
        if (preferredArray != null) appendSections(preferredArray, chinese, out);

        if (out.isEmpty()) {
            Iterator<String> keys = root.keys();
            while (keys.hasNext() && out.size() < MAX_SECTIONS) {
                String code = keys.next();
                JSONArray array = root.optJSONArray(code);
                if (array == null || code.equals(preferred)) continue;
                if (matchesLanguage(code, array, chinese)) appendSections(array, chinese, out);
            }
        }
        return new Result(query, chinese, host, out);
    }

    private static boolean matchesLanguage(String code, JSONArray array, boolean chineseQuery) {
        String c = safe(code).toLowerCase(Locale.ROOT);
        if (chineseQuery && (c.equals("zh") || c.equals("cmn") || c.equals("yue") || c.equals("wuu"))) return true;
        if (!chineseQuery && c.equals("en")) return true;
        for (int i = 0; i < Math.min(3, array.length()); i++) {
            JSONObject section = array.optJSONObject(i);
            if (section == null) continue;
            String language = section.optString("language", "").toLowerCase(Locale.ROOT);
            if (chineseQuery && (language.contains("chinese") || language.contains("mandarin"))) return true;
            if (!chineseQuery && language.contains("english")) return true;
        }
        return false;
    }

    private static void appendSections(JSONArray array, boolean chineseQuery, List<Section> out) {
        for (int i = 0; i < array.length() && out.size() < MAX_SECTIONS; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String language = clean(item.optString("language", ""));
            if (!language.isBlank()) {
                String lower = language.toLowerCase(Locale.ROOT);
                if (chineseQuery && !(lower.contains("chinese") || lower.contains("mandarin"))) continue;
                if (!chineseQuery && !lower.contains("english")) continue;
            }
            String pos = clean(item.optString("partOfSpeech", ""));
            JSONArray defs = item.optJSONArray("definitions");
            if (defs == null) continue;
            ArrayList<Definition> definitions = new ArrayList<>();
            for (int j = 0; j < defs.length() && definitions.size() < MAX_DEFINITIONS_PER_SECTION; j++) {
                JSONObject def = defs.optJSONObject(j);
                if (def == null) continue;
                String text = clean(def.optString("definition", ""));
                if (text.isBlank()) continue;
                String example = "";
                JSONArray examples = def.optJSONArray("examples");
                if (examples != null && examples.length() > 0) {
                    Object first = examples.opt(0);
                    if (first instanceof String) example = clean((String) first);
                    else if (first instanceof JSONObject) {
                        JSONObject o = (JSONObject) first;
                        example = clean(o.optString("example", o.optString("text", "")));
                    }
                }
                definitions.add(new Definition(text, example));
            }
            if (!definitions.isEmpty()) out.add(new Section(language, pos, definitions));
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        StringBuilder out = new StringBuilder(16_384);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8_192];
            int n;
            while ((n = reader.read(buffer)) >= 0) {
                if (n == 0) continue;
                if (out.length() + n > MAX_RESPONSE_CHARS) throw new IOException("在线词典响应过大");
                out.append(buffer, 0, n);
            }
        }
        return out.toString();
    }

    private static String clean(String html) {
        if (html == null || html.isBlank()) return "";
        String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
        return text.replace('\u00A0', ' ').replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private OnlineDictionaryClient() {}
}
