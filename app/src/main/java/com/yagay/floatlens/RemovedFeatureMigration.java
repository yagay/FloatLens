package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Map;

/** One-time cleanup after removing the built-in AI assistant and dictionary features. */
final class RemovedFeatureMigration {
    private static final String PREFS = "floatlens_removed_feature_migration";
    private static final String KEY_DONE = "ai_dictionary_removed_v1";

    static void run(Context context) {
        if (context == null) return;
        Context c = context.getApplicationContext();
        SharedPreferences marker = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (marker.getBoolean(KEY_DONE, false)) return;

        pruneCustomMenu(c);
        removeLegacyGestureActions(c);

        String[] obsoletePrefs = {
                "floatlens_ai",
                "floatlens_ai_assistant",
                "floatlens_web_ai",
                "floatlens_web_ai_sessions",
                "floatlens_builtin_actions",
                "floatlens_online_dictionary",
                "floatlens_dictionary_library"
        };
        for (String name : obsoletePrefs) {
            try { c.deleteSharedPreferences(name); } catch (Throwable ignored) {}
        }

        deleteRecursively(new File(c.getFilesDir(), "dictionary"));
        deleteRecursively(new File(c.getFilesDir(), "dictionary_library"));

        marker.edit().putBoolean(KEY_DONE, true).apply();
        DiagnosticLog.i(c, "APP_MIGRATION", "removed built-in AI/dictionary data and stale menu entries");
    }

    private static void pruneCustomMenu(Context c) {
        SharedPreferences p = c.getSharedPreferences("floatlens_custom_menu", Context.MODE_PRIVATE);
        String raw = p.getString("items", "[]");
        JSONArray kept = new JSONArray();
        int removed = 0;
        try {
            JSONArray items = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                if (isRemovedBuiltin(item)) {
                    removed++;
                    continue;
                }
                kept.put(item);
            }
            SharedPreferences.Editor e = p.edit().putString("items", kept.toString());
            e.remove("builtin_dictionary_seeded_v1");
            e.apply();
        } catch (Throwable t) {
            DiagnosticLog.i(c, "APP_MIGRATION", "custom menu cleanup failed=" + t);
        }
        if (removed > 0) DiagnosticLog.i(c, "APP_MIGRATION", "removed stale custom actions=" + removed);
    }

    private static boolean isRemovedBuiltin(JSONObject item) {
        String id = item.optString("id", "");
        String type = item.optString("type", "");
        String pkg = item.optString("package", "");
        String cls = item.optString("class", "");
        if ("builtin_dictionary".equals(id) || "builtin_ai_assistant".equals(id)) return true;
        if ("dictionary".equals(type)) return true;
        if (!"com.yagay.floatlens".equals(pkg)) return false;
        return cls.endsWith(".DictionaryActivity")
                || cls.endsWith(".DictionarySettingsActivity")
                || cls.endsWith(".DictionaryCatalogActivity")
                || cls.endsWith(".AiAssistantActivity")
                || cls.endsWith(".AiSettingsActivity")
                || cls.endsWith(".EmbeddedWebAiActivity");
    }

    private static void removeLegacyGestureActions(Context c) {
        SharedPreferences p = c.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = null;
        for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && "ai_chat".equals(value)) {
                if (e == null) e = p.edit();
                e.putString(entry.getKey(), ActionId.NONE);
            }
        }
        if (e != null) e.apply();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        try { file.delete(); } catch (Throwable ignored) {}
    }

    private RemovedFeatureMigration() {}
}
