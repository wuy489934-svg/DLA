package com.example.chatdemo.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 未完成任务的轻量 checkpoint；payload 用 Android Keystore AES-GCM 加密保存 */
final class AgentCheckpoint {

    private static final String PREFS = "agent-checkpoint";
    private static final String ACTIVE = "active";
    private static final String REQUEST_ID = "requestId";
    private static final String PAYLOAD = "payload";
    private static final String TASK_STATE = "taskState";
    private static final String KEY_ALIAS = "chatdemo-agent-checkpoint";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private AgentCheckpoint() {}

    static void save(Context context, String requestId, String payload) {
        String stored = encrypt(payload);
        prefs(context).edit()
                .putBoolean(ACTIVE, true)
                .putString(REQUEST_ID, requestId)
                .putString(PAYLOAD, stored == null ? payload : stored)
                .commit();
    }

    static void clear(Context context) {
        prefs(context).edit().clear().commit();
    }

    /** 计划/笔记是长任务的可恢复状态；与 payload 分开保存，避免每次工具事件重写大请求体。 */
    static void saveTaskState(Context context, JSONObject state) {
        if (state == null) {
            return;
        }
        String json = state.toString();
        String stored = encrypt(json);
        prefs(context).edit()
                .putString(TASK_STATE, stored == null ? json : stored)
                .commit();
    }

    static JSONObject taskState(Context context) {
        String stored = prefs(context).getString(TASK_STATE, null);
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        String json = decrypt(stored);
        if (json == null) {
            json = stored.startsWith("{") ? stored : null;
        }
        if (json == null) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Exception error) {
            return null;
        }
    }

    static void clearTaskState(Context context) {
        prefs(context).edit().remove(TASK_STATE).commit();
    }

    static boolean hasPending(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(ACTIVE, false)
                && prefs.getString(REQUEST_ID, null) != null
                && prefs.getString(PAYLOAD, null) != null;
    }

    static String requestId(Context context) {
        return prefs(context).getString(REQUEST_ID, null);
    }

    static String payload(Context context) {
        String stored = prefs(context).getString(PAYLOAD, null);
        if (stored == null) {
            return null;
        }
        String decrypted = decrypt(stored);
        if (decrypted != null) {
            return decrypted;
        }
        // 兼容升级前写入的明文 checkpoint
        return stored.startsWith("{") ? stored : null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encrypt(String plain) {
        try {
            SecretKey key = key();
            if (key == null) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String decrypt(String stored) {
        try {
            SecretKey key = key();
            if (key == null) {
                return null;
            }
            byte[] packed = Base64.decode(stored, Base64.NO_WRAP);
            if (packed.length < 13) {
                return null;
            }
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[packed.length - 12];
            System.arraycopy(packed, 0, iv, 0, 12);
            System.arraycopy(packed, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Throwable error) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
