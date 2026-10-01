package com.aireply.companion.ai;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Secure storage for the AI API key.
 *
 * Strategy (encrypted if possible - strongest available per Android version):
 * - Android 6.0+ (API 23+): AES-256/GCM key generated inside the Android
 *   Keystore (hardware-backed where the device supports it). The key never
 *   leaves the keystore; only this app on this device can decrypt.
 * - Android 5.x (API 22-): AndroidKeyStore cannot hold AES keys, so we
 *   derive an AES-256 key with PBKDF2WithHmacSHA256 from a device-scoped
 *   salt (ANDROID_ID) plus a fixed app salt. Best-effort obfuscation - the
 *   ciphertext stored in prefs is still NOT the plaintext key.
 *
 * Ciphertext format:  "ENC1:" + base64( 12-byte GCM IV || ciphertext+tag )
 * Anything not starting with the prefix is treated as legacy plaintext and
 * re-encrypted transparently on next save.
 *
 * The encrypted blob lives in the app's private prefs, so even if prefs are
 * backed up / copied to another device, decryption fails (different keystore
 * key / different ANDROID_ID salt).
 */
public final class CryptoStore {

    private static final String PREFIX = "ENC1:";
    private static final String KS_ALIAS = "aireply_ai_key_v1";
    private static final String KS_PROVIDER = "AndroidKeyStore";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private CryptoStore() {
    }

    /** Encrypts {@code plain}; never throws - returns the input unchanged on fatal errors. */
    public static String encrypt(Context ctx, String plain) {
        if (plain == null) return "";
        if (plain.length() == 0) return "";
        try {
            SecretKey key = getOrCreateKey(ctx);
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            AiLogger.e(ctx, "CryptoStore.encrypt failed", t);
            return plain; // fail "open" so the feature still works rather than corrupting the key
        }
    }

    /** Decrypts a blob produced by {@link #encrypt}; never throws. */
    public static String decrypt(Context ctx, String stored) {
        if (stored == null || stored.length() == 0) return "";
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext written before encryption existed - surface as-is.
            return stored;
        }
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            byte[] iv = new byte[GCM_IV_LEN];
            System.arraycopy(all, 0, iv, 0, GCM_IV_LEN);
            byte[] ct = new byte[all.length - GCM_IV_LEN];
            System.arraycopy(all, GCM_IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getOrCreateKey(ctx), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            AiLogger.e(ctx, "CryptoStore.decrypt failed (wrong device / keystore reset?)", t);
            return "";
        }
    }

    // ------------------------------------------------------------------ keys

    private static SecretKey getOrCreateKey(Context ctx) throws Exception {
        if (Build.VERSION.SDK_INT >= 23) {
            return keystoreAesKey();
        }
        return derivedKey(ctx);
    }

    private static SecretKey keystoreAesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS_PROVIDER);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KS_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance("AES", KS_PROVIDER);
        kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                KS_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // we supply our own IV via GCMParameterSpec
                .build());
        return kg.generateKey();
    }

    private static SecretKey derivedKey(Context ctx) throws Exception {
        String androidId = "aireply";
        try {
            String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 0) androidId = id;
        } catch (Throwable ignored) {
        }
        // Note: ANDROID_ID changes after factory reset, which simply invalidates
        // stored ciphertexts - an acceptable trade-off.
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(("ai:" + androidId).toCharArray(),
                "AIReply-Companion-v1".getBytes("UTF-8"), 20000, 256);
        byte[] keyBytes = f.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
}
