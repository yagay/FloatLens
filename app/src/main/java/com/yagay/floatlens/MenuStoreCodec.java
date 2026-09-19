package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared JSON persistence codec for ordered menu stores. */
final class MenuStoreCodec {
    interface Decoder<T> { T decode(JSONObject object); }
    interface Encoder<T> { JSONObject encode(T item); }

    static <T> List<T> loadList(Context context, SharedPreferences prefs, String key,
                                Decoder<T> decoder, String tag) {
        ArrayList<T> out = new ArrayList<>();
        if (prefs == null || key == null || decoder == null) return out;
        String raw = prefs.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                T item = decoder.decode(object);
                if (item != null) out.add(item);
            }
        } catch (Throwable t) {
            if (context != null) DiagnosticLog.i(context, tag, "load key=" + key + " failed=" + t);
        }
        return out;
    }

    static <T> void saveList(SharedPreferences prefs, String key, List<T> items,
                             Encoder<T> encoder) {
        if (prefs == null || key == null || encoder == null) return;
        JSONArray array = new JSONArray();
        if (items != null) {
            for (T item : items) {
                JSONObject object = encoder.encode(item);
                if (object != null) array.put(object);
            }
        }
        prefs.edit().putString(key, array.toString()).apply();
    }

    static Map<String, String> loadStringMap(Context context, SharedPreferences prefs,
                                             String key, String tag) {
        HashMap<String, String> out = new HashMap<>();
        if (prefs == null || key == null) return out;
        String raw = prefs.getString(key, "{}");
        try {
            JSONObject object = new JSONObject(raw == null ? "{}" : raw);
            var keys = object.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                String value = object.optString(name, "").trim();
                if (!name.isBlank() && !value.isBlank()) out.put(name, value);
            }
        } catch (Throwable t) {
            if (context != null) DiagnosticLog.i(context, tag, "load map key=" + key + " failed=" + t);
        }
        return out;
    }

    static void saveStringMap(SharedPreferences prefs, String key, Map<String, String> values) {
        if (prefs == null || key == null) return;
        JSONObject object = new JSONObject();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                try { object.put(entry.getKey(), entry.getValue()); }
                catch (Throwable ignored) { }
            }
        }
        prefs.edit().putString(key, object.toString()).apply();
    }

    private MenuStoreCodec() {}
}
