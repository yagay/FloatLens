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
    public static final String PROVIDER_TOKEN_FREE = "token_free_gateway";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_GROQ = "groq";
    public static final String PROVIDER_CUSTOM = "custom";

    private static final String PREFS = "floatlens_ai";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_BASE_URL_LEGACY = "base_url";
    private static final String KEY_BASE_URL_PREFIX = "base_url_";
    private static final String KEY_MODEL_LEGACY = "model";
    private static final String KEY_MODEL_PREFIX = "model_";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";

    // v1 used one shared API key. Keep it as an OpenRouter migration fallback only.
    private static final String KEY_API_KEY_CIPHER_LEGACY = "api_key_cipher";
    private static final String KEY_API_KEY_IV_LEGACY = "api_key_iv";
    private static final String KEY_API_KEY_CIPHER_PREFIX = "api_key_cipher_";
    private static final String KEY_API_KEY_IV_PREFIX = "api_key_iv_";
    private static final String KEYSTORE_ALIAS_LEGACY = "floatlens_ai_api_key_v1";
    private static final String KEYSTORE_ALIAS_PREFIX = "floatlens_ai_api_key_v2_";

    private static final String TOKEN_FREE_BASE = "http://127.0.0.1:3456/v1";
    private static final String OPENROUTER_BASE = "https://openrouter.ai/api/v1";
    private static final String OPENROUTER_MODEL = "openrouter/free";
    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String GEMINI_MODEL = "gemini-3.8-flash";
    private static final String GROQ_BASE = "https://api.groq.com/openai/v1";
    private static final String GROQ_MODEL = "qwen/qwen3.8-27b";
    private static final String DEFAULT_SYSTEM = "You are a concise helpful assistant inside FloatLens. Reply in the user's language unless they ask for another language.";

    public static String provider(Context c) {
        return normalizeProvider(prefs(c).getString(KEY_PROVIDER, PROVIDER_OPENROUTER));
    }

    public static void setProvider(Context c, String value) {
        prefs(c).edit().putString(KEY_PROVIDER, normalizeProvider(value)).apply();
    }

    public static String providerLabel(Context c) {
        return providerLabel(provider(c));
    }

    public static String providerLabel(String provider) {
        return switch (normalizeProvider(provider)) {
            case PROVIDER_TOKEN_FREE -> "Token-Free Gateway · Web AI";
            case PROVIDER_GEMINI -> "Gemini · Free Tier";
            case PROVIDER_GROQ -> "Groq · Free Tier";
            case PROVIDER_CUSTOM -> "自定义 OpenAI Compatible";
            default -> "OpenRouter · Free Models Router";
        };
    }

    public static String baseUrl(Context c) {
        return baseUrl(c, provider(c));
    }

    public static String baseUrl(Context c, String provider) {
        String p = normalizeProvider(provider);
        if (PROVIDER_OPENROUTER.equals(p)) return OPENROUTER_BASE;
        if (PROVIDER_GEMINI.equals(p)) return GEMINI_BASE;
        if (PROVIDER_GROQ.equals(p)) return GROQ_BASE;

        SharedPreferences preferences = prefs(c);
        String value = preferences.getString(KEY_BASE_URL_PREFIX + p, "");
        value = value == null ? "" : value.trim();
        if (!value.isEmpty()) return normalizeBaseForProvider(p, value);

        // Preserve the custom Base URL saved before per-provider URLs were introduced.
        if (PROVIDER_CUSTOM.equals(p)) {
            String legacy = preferences.getString(KEY_BASE_URL_LEGACY, "");
            legacy = legacy == null ? "" : legacy.trim();
            if (!legacy.isEmpty()) return legacy;
        }
        return defaultBase(p);
    }

    public static void setBaseUrl(Context c, String value) {
        setBaseUrl(c, provider(c), value);
    }

    public static void setBaseUrl(Context c, String provider, String value) {
        String p = normalizeProvider(provider);
        String clean = value == null ? "" : value.trim();
        clean = normalizeBaseForProvider(p, clean);
        prefs(c).edit().putString(KEY_BASE_URL_PREFIX + p, clean).apply();
    }

    public static String model(Context c) {
        return model(c, provider(c));
    }

    public static String model(Context c, String provider) {
        String p = normalizeProvider(provider);
        SharedPreferences preferences = prefs(c);
        String saved = preferences.getString(KEY_MODEL_PREFIX + p, "");
        saved = saved == null ? "" : saved.trim();
        if (!saved.isEmpty()) return saved;

        // Preserve the model selected before multi-provider support. That version only had OpenRouter/custom.
        if (PROVIDER_OPENROUTER.equals(p)) {
            String legacy = preferences.getString(KEY_MODEL_LEGACY, "");
            legacy = legacy == null ? "" : legacy.trim();
            if (!legacy.isEmpty()) return legacy;
        }
        return defaultModel(p);
    }

    public static void setModel(Context c, String value) {
        setModel(c, provider(c), value);
    }

    public static void setModel(Context c, String provider, String value) {
        String p = normalizeProvider(provider);
        prefs(c).edit().putString(KEY_MODEL_PREFIX + p, value == null ? "" : value.trim()).apply();
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
        return apiKey(c, provider(c));
    }

    public static String apiKey(Context c, String provider) {
        if (c == null) return "";
        String p = normalizeProvider(provider);
        String value = decryptStored(c,
                KEY_API_KEY_CIPHER_PREFIX + p,
                KEY_API_KEY_IV_PREFIX + p,
                KEYSTORE_ALIAS_PREFIX + p);
        if (!value.isBlank()) return value;

        // Migrate the old single key only as OpenRouter's key. Never reuse it for other providers.
        if (PROVIDER_OPENROUTER.equals(p)) {
            return decryptStored(c,
                    KEY_API_KEY_CIPHER_LEGACY,
                    KEY_API_KEY_IV_LEGACY,
                    KEYSTORE_ALIAS_LEGACY);
        }
        return "";
    }

    public static boolean hasApiKey(Context c) {
        return hasApiKey(c, provider(c));
    }

    public static boolean hasApiKey(Context c, String provider) {
        return !apiKey(c, provider).isBlank();
    }

    public static void setApiKey(Context c, String value) throws Exception {
        setApiKey(c, provider(c), value);
    }

    public static void setApiKey(Context c, String provider, String value) throws Exception {
        if (c == null) return;
        String p = normalizeProvider(provider);
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            clearApiKey(c, p);
            return;
        }
        String alias = KEYSTORE_ALIAS_PREFIX + p;
        SecretKey key = getOrCreateKey(alias);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
        prefs(c).edit()
                .putString(KEY_API_KEY_CIPHER_PREFIX + p, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_API_KEY_IV_PREFIX + p, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    public static void clearApiKey(Context c) {
        if (c != null) clearApiKey(c, provider(c));
    }

    public static void clearApiKey(Context c, String provider) {
        if (c == null) return;
        String p = normalizeProvider(provider);
        SharedPreferences.Editor editor = prefs(c).edit()
                .remove(KEY_API_KEY_CIPHER_PREFIX + p)
                .remove(KEY_API_KEY_IV_PREFIX + p);
        if (PROVIDER_OPENROUTER.equals(p)) {
            editor.remove(KEY_API_KEY_CIPHER_LEGACY).remove(KEY_API_KEY_IV_LEGACY);
        }
        editor.apply();
    }

    public static boolean isConfigured(Context c) {
        return isConfigured(c, provider(c));
    }

    public static boolean isConfigured(Context c, String provider) {
        String p = normalizeProvider(provider);
        if (PROVIDER_CUSTOM.equals(p) || PROVIDER_TOKEN_FREE.equals(p)) {
            return !baseUrl(c, p).isBlank() && !model(c, p).isBlank();
        }
        return hasApiKey(c, p) && !model(c, p).isBlank();
    }

    public static String endpoint(Context c) {
        return endpoint(c, provider(c));
    }

    public static String endpoint(Context c, String provider) {
        String base = apiBase(c, provider);
        if (base.endsWith("/chat/completions")) return base;
        return base + "/chat/completions";
    }

    public static String modelsEndpoint(Context c, String provider) {
        return apiBase(c, provider) + "/models";
    }

    private static String apiBase(Context c, String provider) {
        String p = normalizeProvider(provider);
        String base = normalizeBaseForProvider(p, baseUrl(c, p));
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        if (base.endsWith("/models")) {
            base = base.substring(0, base.length() - "/models".length());
        }
        return base;
    }

    public static String defaultBase(String provider) {
        return switch (normalizeProvider(provider)) {
            case PROVIDER_TOKEN_FREE -> TOKEN_FREE_BASE;
            case PROVIDER_GEMINI -> GEMINI_BASE;
            case PROVIDER_GROQ -> GROQ_BASE;
            case PROVIDER_CUSTOM -> "";
            default -> OPENROUTER_BASE;
        };
    }

    public static String defaultModel(String provider) {
        return switch (normalizeProvider(provider)) {
            case PROVIDER_TOKEN_FREE, PROVIDER_CUSTOM -> "";
            case PROVIDER_GEMINI -> GEMINI_MODEL;
            case PROVIDER_GROQ -> GROQ_MODEL;
            default -> OPENROUTER_MODEL;
        };
    }

    public static String defaultOpenRouterBase() { return OPENROUTER_BASE; }
    public static String defaultOpenRouterModel() { return OPENROUTER_MODEL; }

    private static String normalizeBaseForProvider(String provider, String value) {
        String base = value == null ? "" : value.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (PROVIDER_TOKEN_FREE.equals(provider) && !base.isEmpty()
                && !base.endsWith("/v1")
                && !base.endsWith("/v1/chat/completions")
                && !base.endsWith("/v1/models")) {
            base += "/v1";
        }
        return base;
    }

    private static String decryptStored(Context c, String cipherPref, String ivPref, String alias) {
        SharedPreferences p = prefs(c);
        String cipherText = p.getString(cipherPref, "");
        String ivText = p.getString(ivPref, "");
        if (cipherText == null || cipherText.isBlank() || ivText == null || ivText.isBlank()) return "";
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(alias, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            DiagnosticLog.i(c, "AI_CONFIG", "decrypt " + alias + " failed=" + t.getClass().getSimpleName());
            return "";
        }
    }

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(alias, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String normalizeProvider(String value) {
        if (PROVIDER_TOKEN_FREE.equals(value)) return PROVIDER_TOKEN_FREE;
        if (PROVIDER_GEMINI.equals(value)) return PROVIDER_GEMINI;
        if (PROVIDER_GROQ.equals(value)) return PROVIDER_GROQ;
        if (PROVIDER_CUSTOM.equals(value)) return PROVIDER_CUSTOM;
        return PROVIDER_OPENROUTER;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private AiConfigStore() {}
}
