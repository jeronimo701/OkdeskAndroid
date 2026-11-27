package ru.poscenter.okdesk.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStore {
    private static final String KEY_ALIAS = "okdesk_mobile_token_key";
    private static final String TOKEN_PREFIX = "okdesk_api_token";
    private final SharedPreferences preferences;

    SecureStore(Context context) {
        preferences = context.getSharedPreferences("userscript_values", Context.MODE_PRIVATE);
    }

    JSONObject snapshot() throws JSONException {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith("native_login_")) continue;
            if (!(entry.getValue() instanceof String)) continue;
            try {
                String raw = (String) entry.getValue();
                if (isToken(entry.getKey())) raw = decrypt(raw);
                result.put(entry.getKey(), new JSONObject(raw).opt("value"));
            } catch (Exception ignored) {
                // A damaged or undecryptable entry is not exposed to JavaScript.
            }
        }
        if (!result.has("okdesk_mobile_view_shtrih-m.okdesk.ru")) {
            result.put("okdesk_mobile_view_shtrih-m.okdesk.ru", "on");
        }
        return result;
    }

    void put(String key, Object value) throws Exception {
        if (key == null || key.length() > 128 || value == null) throw new IllegalArgumentException("Invalid setting");
        String raw = new JSONObject().put("value", value).toString();
        if (raw.length() > 2_000_000) throw new IllegalArgumentException("Setting is too large");
        if (isToken(key) || key.startsWith("native_login_")) raw = encrypt(raw);
        if (!preferences.edit().putString(key, raw).commit()) throw new IllegalStateException("Could not save setting");
    }

    void remove(String key) {
        if (key != null && key.length() <= 128) preferences.edit().remove(key).apply();
    }

    static String loginKey(String origin) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder("native_login_");
        for (byte b : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return key.toString();
    }

    JSONObject login(String origin) throws Exception {
        String raw = preferences.getString(loginKey(origin), null);
        return raw == null ? new JSONObject() : new JSONObject(decrypt(raw)).getJSONObject("value");
    }

    private static boolean isToken(String key) {
        return key.equals(TOKEN_PREFIX) || key.startsWith(TOKEN_PREFIX + "_");
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
        if (combined.length < 29) throw new IllegalArgumentException("Invalid encrypted setting");
        byte[] iv = new byte[12];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(combined, iv.length, combined.length - iv.length), StandardCharsets.UTF_8);
    }
}
