package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores AI settings. API keys are encrypted with Android Keystore and never hard-coded. */
public final class AiConfigStore {
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_CUSTOM = "custom";

    private static final String PREFS = "floatlens_ai";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_API_KEY_CIPHER = "api_key_cipher";
    private static final String KEY_API_KEY_IV = "api_key_iv";
    private static final String KEYSTORE_ALIAS = "floatlens_ai_api_key_v1";

    private static final String OPENROUTER_BASE = "https://openrouter.ai/api/v1";
    private static final String OPENROUTER_MODEL = "openrouter/free";
    private static final String DEFAULT_SYSTEM = "You are a concise helpful assistant inside FloatLens. Reply in the user's language unless they ask for another language.";

    public static String provider(Context c) {
        String value = prefs(c).getString(KEY_PROVIDER, PROVIDER_OPENROUTER);
        return PROVIDER_CUSTOM.equals(value) ? PROVIDER_CUSTOM : PROVIDER_OPENROUTER;
    }

    public static void setProvider(Context c, String value) {
        prefs(c).edit().putString(KEY_PROVIDER,
                PROVIDER_CUSTOM.equals(value) ? PROVIDER_CUSTOM : PROVIDER_OPENROUTER).apply();
    }

    public static String providerLabel(Context c) {
        return PROVIDER_CUSTOM.equals(provider(c)) ? "自定义 OpenAI Compatible" : "OpenRouter Free";
    }

    public static String baseUrl(Context c) {
        if (PROVIDER_OPENROUTER.equals(provider(c))) return OPENROUTER_BASE;
        String value = prefs(c).getString(KEY_BASE_URL, "");
        return value == null ? "" : value.trim();
    }

    public static void setBaseUrl(Context c, String value) {
        prefs(c).edit().putString(KEY_BASE_URL, value == null ? "" : value.trim()).apply();
    }

    public static String model(Context c) {
        String saved = prefs(c).getString(KEY_MODEL, "");
        saved = saved == null ? "" : saved.trim();
        if (!saved.isEmpty()) return saved;
        return PROVIDER_OPENROUTER.equals(provider(c)) ? OPENROUTER_MODEL : "";
    }

    public static void setModel(Context c, String value) {
        prefs(c).edit().putString(KEY_MODEL, value == null ? "" : value.trim()).apply();
    }

    public static String systemPrompt(Context c) {
        String value = prefs(c).getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM);
        return value == null || value.isBlank() ? DEFAULT_SYSTEM : value.trim();
    }

    public static void setSystemPrompt(Context c, String value) {
        prefs(c).edit().putString(KEY_SYSTEM_PROMPT,
                value == null || value.isBlank() ? DEFAULT_SYSTEM : value.trim()).apply();
    }

    public static String apiKey(Context c) {
        if (c == null) return "";
        SharedPreferences p = prefs(c);
        String cipherText = p.getString(KEY_API_KEY_CIPHER, "");
        String ivText = p.getString(KEY_API_KEY_IV, "");
        if (cipherText == null || cipherText.isBlank() || ivText == null || ivText.isBlank()) return "";
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(KEYSTORE_ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            DiagnosticLog.i(c, "AI_CONFIG", "decrypt api key failed=" + t.getClass().getSimpleName());
            return "";
        }
    }

    public static boolean hasApiKey(Context c) { return !apiKey(c).isBlank(); }

    public static void setApiKey(Context c, String value) throws Exception {
        if (c == null) return;
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            clearApiKey(c);
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
        prefs(c).edit()
                .putString(KEY_API_KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_API_KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    public static void clearApiKey(Context c) {
        if (c != null) prefs(c).edit().remove(KEY_API_KEY_CIPHER).remove(KEY_API_KEY_IV).apply();
    }

    public static boolean isConfigured(Context c) {
        if (PROVIDER_OPENROUTER.equals(provider(c))) {
            return hasApiKey(c) && !model(c).isBlank();
        }
        return !baseUrl(c).isBlank() && !model(c).isBlank();
    }

    public static String endpoint(Context c) {
        String base = baseUrl(c).trim();
        if (base.endsWith("/chat/completions")) return base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/chat/completions";
    }

    public static String defaultOpenRouterBase() { return OPENROUTER_BASE; }
    public static String defaultOpenRouterModel() { return OPENROUTER_MODEL; }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEYSTORE_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private AiConfigStore() {}
}
