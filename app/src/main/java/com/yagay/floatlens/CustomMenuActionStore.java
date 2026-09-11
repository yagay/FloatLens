package com.yagay.floatlens;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent FloatLens custom text-menu actions selected from discovered apps/intent handlers. */
public final class CustomMenuActionStore {
    private static final String PREFS = "floatlens_custom_menu";
    private static final String KEY_ITEMS = "items";

    public static final String TYPE_LAUNCH = "launch";
    public static final String TYPE_ACTIVITY = "activity";
    public static final String TYPE_SEND_TEXT = "send_text";
    public static final String TYPE_PROCESS_TEXT = "process_text";
    public static final String TYPE_VIEW_WEB = "view_web";
    public static final String TYPE_WEB_SEARCH = "web_search";
    public static final String TYPE_DIAL = "dial";
    public static final String TYPE_SMS = "sms";
    public static final String TYPE_EMAIL = "email";
    public static final String TYPE_MAP = "map";
    public static final String TYPE_TRANSLATE = "translate";
    public static final String TYPE_VIEW_TEXT = "view_text";

    public static final class Item {
        public final String id;
        public final String label;
        public final String packageName;
        public final String className;
        public final String type;

        public Item(String id, String label, String packageName, String className, String type) {
            this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            this.label = label == null ? "应用" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
            this.type = type == null ? TYPE_ACTIVITY : type;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("label", label);
                o.put("package", packageName);
                o.put("class", className);
                o.put("type", type);
            } catch (Throwable ignored) {}
            return o;
        }

        static Item fromJson(JSONObject o) {
            if (o == null) return null;
            return new Item(o.optString("id"), o.optString("label"), o.optString("package"),
                    o.optString("class"), o.optString("type", TYPE_ACTIVITY));
        }

        public String stableKey() {
            return type + "|" + packageName + "|" + className;
        }
    }

    public static List<Item> load(Context c) {
        ArrayList<Item> out = new ArrayList<>();
        if (c == null) return out;
        String raw = prefs(c).getString(KEY_ITEMS, "[]");
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                Item item = Item.fromJson(a.optJSONObject(i));
                if (item != null && !item.packageName.isBlank()) out.add(item);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(c, "CUSTOM_MENU", "load failed=" + t);
        }
        return out;
    }

    public static boolean add(Context c, Item item) {
        if (c == null || item == null || item.packageName.isBlank()) return false;
        List<Item> items = load(c);
        for (Item old : items) {
            if (old.stableKey().equals(item.stableKey())) return false;
        }
        items.add(item);
        save(c, items);
        return true;
    }

    public static void remove(Context c, String id) {
        List<Item> items = load(c);
        items.removeIf(i -> i.id.equals(id));
        save(c, items);
    }

    private static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        if (items != null) for (Item item : items) a.put(item.toJson());
        prefs(c).edit().putString(KEY_ITEMS, a.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean launch(Context c, Item item, String selectedText) {
        if (c == null || item == null) return false;
        try {
            Intent intent = buildIntent(c, item, selectedText == null ? "" : selectedText.trim());
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!item.className.isBlank()) {
                intent.setClassName(item.packageName, item.className);
            } else if (!item.packageName.isBlank()) {
                intent.setPackage(item.packageName);
            }
            c.startActivity(intent);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(c, "CUSTOM_MENU", "launch failed " + item.stableKey() + " " + t);
            Toast.makeText(c, "无法打开 " + item.label, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static Intent buildIntent(Context c, Item item, String text) {
        switch (item.type) {
            case TYPE_LAUNCH -> {
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(item.packageName);
                if (launch != null) return launch;
                return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            }
            case TYPE_ACTIVITY -> {
                return new Intent();
            }
            case TYPE_SEND_TEXT -> {
                return new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text);
            }
            case TYPE_PROCESS_TEXT -> {
                return new Intent(Intent.ACTION_PROCESS_TEXT)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
            }
            case TYPE_VIEW_WEB -> {
                return new Intent(Intent.ACTION_VIEW, Uri.parse(toWebUri(text)));
            }
            case TYPE_WEB_SEARCH -> {
                return new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text);
            }
            case TYPE_DIAL -> {
                return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(text)));
            }
            case TYPE_SMS -> {
                return new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                        .putExtra("sms_body", text);
            }
            case TYPE_EMAIL -> {
                return new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("mailto:?body=" + Uri.encode(text)));
            }
            case TYPE_MAP -> {
                return new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=" + Uri.encode(text)));
            }
            case TYPE_TRANSLATE -> {
                return new Intent("android.intent.action.TRANSLATE")
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text);
            }
            case TYPE_VIEW_TEXT -> {
                return new Intent(Intent.ACTION_VIEW)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text);
            }
            default -> {
                return null;
            }
        }
    }

    private static String toWebUri(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.matches("(?i)^[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$")) return "https://" + value;
        return "https://www.google.com/search?q=" + Uri.encode(value);
    }

    public static String typeLabel(String type) {
        if (type == null) return "直接打开";
        return switch (type) {
            case TYPE_LAUNCH -> "启动应用";
            case TYPE_ACTIVITY -> "直接打开入口";
            case TYPE_SEND_TEXT -> "分享文字";
            case TYPE_PROCESS_TEXT -> "处理文字";
            case TYPE_VIEW_WEB -> "网页打开/搜索";
            case TYPE_WEB_SEARCH -> "网页搜索";
            case TYPE_DIAL -> "拨号";
            case TYPE_SMS -> "短信";
            case TYPE_EMAIL -> "邮件";
            case TYPE_MAP -> "地图搜索";
            case TYPE_TRANSLATE -> "翻译";
            case TYPE_VIEW_TEXT -> "打开纯文本";
            default -> type;
        };
    }

    private CustomMenuActionStore() {}
}
