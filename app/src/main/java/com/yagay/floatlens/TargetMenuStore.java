package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistent ordered targets for FloatLens share/process submenus. */
public final class TargetMenuStore {
    public static final String MODE_SHARE = "share";
    public static final String MODE_PROCESS = "process";

    private static final String PREFS = "floatlens_target_menus";

    public static final class Item {
        public final String label;
        public final String packageName;
        public final String className;

        public Item(String label, String packageName, String className) {
            this.label = label == null || label.isBlank() ? "应用" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
        }

        public String key() {
            return packageName + "|" + className;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("label", label);
                o.put("package", packageName);
                o.put("class", className);
            } catch (Throwable ignored) {}
            return o;
        }

        static Item fromJson(JSONObject o) {
            if (o == null) return null;
            return new Item(o.optString("label"), o.optString("package"), o.optString("class"));
        }
    }

    public static boolean isCustomized(Context c, String mode) {
        return prefs(c).getBoolean(customizedKey(mode), false);
    }

    public static List<Item> load(Context c, String mode) {
        ArrayList<Item> out = new ArrayList<>();
        String raw = prefs(c).getString(itemsKey(mode), "[]");
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                Item item = Item.fromJson(a.optJSONObject(i));
                if (item != null && !item.packageName.isBlank() && !item.className.isBlank()) out.add(item);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(c, "TARGET_MENU", "load " + mode + " failed=" + t);
        }
        return out;
    }

    public static void save(Context c, String mode, List<Item> items) {
        JSONArray a = new JSONArray();
        if (items != null) for (Item item : items) a.put(item.toJson());
        prefs(c).edit()
                .putBoolean(customizedKey(mode), true)
                .putString(itemsKey(mode), a.toString())
                .apply();
    }

    public static void reset(Context c, String mode) {
        prefs(c).edit()
                .putBoolean(customizedKey(mode), false)
                .remove(itemsKey(mode))
                .apply();
    }

    public static boolean remove(Context c, String mode, String key) {
        List<Item> items = load(c, mode);
        boolean changed = items.removeIf(i -> i.key().equals(key));
        if (changed) save(c, mode, items);
        return changed;
    }

    public static boolean add(Context c, String mode, Item item) {
        if (item == null || item.packageName.isBlank() || item.className.isBlank()) return false;
        List<Item> items = load(c, mode);
        for (Item old : items) if (old.key().equals(item.key())) return false;
        items.add(item);
        save(c, mode, items);
        return true;
    }

    public static boolean move(Context c, String mode, String key, int delta) {
        if (delta == 0) return false;
        List<Item> items = load(c, mode);
        int from = indexOf(items, key);
        if (from < 0) return false;
        int to = Math.max(0, Math.min(items.size() - 1, from + delta));
        if (from == to) return false;
        Item item = items.remove(from);
        items.add(to, item);
        save(c, mode, items);
        return true;
    }

    public static boolean moveTo(Context c, String mode, String key, int targetIndex) {
        List<Item> items = load(c, mode);
        int from = indexOf(items, key);
        if (from < 0 || items.isEmpty()) return false;
        int to = Math.max(0, Math.min(items.size() - 1, targetIndex));
        if (from == to) return false;
        Item item = items.remove(from);
        if (to > items.size()) to = items.size();
        items.add(to, item);
        save(c, mode, items);
        return true;
    }

    private static int indexOf(List<Item> items, String key) {
        if (items == null || key == null) return -1;
        for (int i = 0; i < items.size(); i++) if (key.equals(items.get(i).key())) return i;
        return -1;
    }

    private static String itemsKey(String mode) {
        return "items_" + safeMode(mode);
    }

    private static String customizedKey(String mode) {
        return "customized_" + safeMode(mode);
    }

    private static String safeMode(String mode) {
        return MODE_PROCESS.equals(mode) ? MODE_PROCESS : MODE_SHARE;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private TargetMenuStore() {}
}
