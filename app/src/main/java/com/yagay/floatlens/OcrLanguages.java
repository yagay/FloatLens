package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * OCR language preference shared by Settings and the existing OCR pipeline.
 *
 * ML Kit exposes one Chinese recognizer for both Simplified/Traditional Chinese,
 * while PP-OCRv6 uses a single multilingual recognition model. We still keep
 * zh-Hans and zh-Hant as separate user preferences so the UI matches the
 * languages the user wants to recognize and the preference can be consumed by
 * stricter per-script routing in the future without another migration.
 */
public final class OcrLanguages {
    public static final String KEY = "ocr_languages_v2";
    public static final String ZH_HANS = "zh-Hans";
    public static final String ZH_HANT = "zh-Hant";
    public static final String ENGLISH = "en";

    private static final Set<String> ALLOWED = Set.of(ZH_HANS, ZH_HANT, ENGLISH);

    public static Set<String> get(Context context) {
        SharedPreferences p = prefs(context);
        Set<String> stored = null;
        try {
            stored = p.getStringSet(KEY, null);
        } catch (ClassCastException ignored) {
            p.edit().remove(KEY).apply();
        }

        Set<String> out = sanitize(stored);
        if (!out.isEmpty()) return out;

        // Migrate the old two-state setting without changing existing behaviour.
        // old=1 meant Latin-only; old=0 meant Chinese + Latin.
        int legacy = readLegacyType(p);
        if (legacy == 1) {
            out.add(ENGLISH);
        } else {
            out.add(ZH_HANS);
            out.add(ZH_HANT);
            out.add(ENGLISH);
        }
        persist(p, out);
        return new HashSet<>(out);
    }

    public static Set<String> save(Context context, Set<String> values) {
        Set<String> out = sanitize(values);
        if (out.isEmpty()) out.add(ENGLISH);
        persist(prefs(context), out);
        return new HashSet<>(out);
    }

    public static boolean chineseEnabled(Set<String> values) {
        return values != null && (values.contains(ZH_HANS) || values.contains(ZH_HANT));
    }

    public static boolean englishEnabled(Set<String> values) {
        return values != null && values.contains(ENGLISH);
    }

    public static Set<String> all() {
        return new HashSet<>(ALLOWED);
    }

    public static Set<String> allowed() {
        return Collections.unmodifiableSet(ALLOWED);
    }

    private static Set<String> sanitize(Set<String> values) {
        Set<String> out = new HashSet<>();
        if (values == null) return out;
        for (String value : values) if (ALLOWED.contains(value)) out.add(value);
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
    }

    private static int readLegacyType(SharedPreferences p) {
        try {
            Object raw = p.getAll().get(FloatSettings.K_OCR_TYPE);
            if (raw instanceof Number) return ((Number) raw).intValue() == 1 ? 1 : 0;
            if (raw instanceof String) return Integer.parseInt(((String) raw).trim()) == 1 ? 1 : 0;
            if (raw instanceof Boolean) return ((Boolean) raw) ? 1 : 0;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void persist(SharedPreferences p, Set<String> values) {
        Set<String> copy = new HashSet<>(values);
        boolean anyChinese = chineseEnabled(copy);
        // Keep the old preference synchronized so older OcrEngine builds and any
        // compatibility code still choose the right recognizer family.
        int legacyType = anyChinese ? 0 : 1;
        p.edit()
                .putStringSet(KEY, copy)
                .putInt(FloatSettings.K_OCR_TYPE, legacyType)
                .apply();
    }

    private OcrLanguages() {
    }
}
