package com.research.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** AES-256-GCM encryption for sensitive settings. */
@Component
public class Encryptor {

    private static final Logger log = LoggerFactory.getLogger(Encryptor.class);
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String CIPHER_PREFIX = "enc:";

    private final String masterKey;
    private final boolean failClosed;

    /** Local development compatibility constructor. */
    public Encryptor(String masterKey) {
        this(masterKey, false);
    }

    public Encryptor(String masterKey, boolean failClosed) {
        this.masterKey = masterKey;
        this.failClosed = failClosed;
        if (masterKey == null || masterKey.isBlank()) {
            if (failClosed) {
                throw new IllegalStateException("RA_MASTER_KEY 未配置，生产环境拒绝启动");
            }
            log.warn("RA_MASTER_KEY 未配置，当前处于本地开发明文兼容模式");
        }
    }

    public boolean shouldEncrypt(String keyName) {
        if (keyName == null) return false;
        String lower = keyName.toLowerCase(Locale.ROOT);
        return lower.contains("api_key")
                || lower.endsWith("_token")
                || lower.endsWith("_secret")
                || lower.endsWith("_password")
                || "zotero_collection_key".equals(lower);
    }

    public String encryptIfNeeded(String keyName, String value) {
        if (!shouldEncrypt(keyName) || value == null || value.isBlank()) return value;
        if (value.startsWith(CIPHER_PREFIX)) return value;
        return encrypt(value);
    }

    public String decryptIfNeeded(String value) {
        if (value == null || !value.startsWith(CIPHER_PREFIX)) return value;
        return decrypt(value.substring(CIPHER_PREFIX.length()));
    }

    private String encrypt(String plaintext) {
        if (!isReady()) {
            if (failClosed) throw new IllegalStateException("加密主密钥未配置");
            return plaintext;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return CIPHER_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("敏感设置加密失败 type={}", e.getClass().getSimpleName());
            if (failClosed) throw new IllegalStateException("敏感设置加密失败", e);
            return plaintext;
        }
    }

    private String decrypt(String base64) {
        if (!isReady()) {
            if (failClosed) throw new IllegalStateException("加密主密钥未配置");
            log.warn("加密主密钥未配置，无法解密敏感设置");
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(base64);
            if (combined.length < GCM_IV_LENGTH) return null;
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherBytes = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("敏感设置解密失败 type={}", e.getClass().getSimpleName());
            if (failClosed) throw new IllegalStateException("敏感设置解密失败", e);
            return null;
        }
    }

    private boolean isReady() {
        return masterKey != null && !masterKey.isBlank();
    }

    private SecretKeySpec secretKey() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}
