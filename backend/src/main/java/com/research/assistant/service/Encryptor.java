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

/**
 * 设置值加密器 —— 使用 AES-256-GCM。
 * <p>
 * 主密钥由环境变量 {@code RA_MASTER_KEY} 或 {@code app.encryption.master-key} 提供；
 * 未配置时所有加密/解密操作返回原文，并记录警告。
 */
@Component
public class Encryptor {

    private static final Logger log = LoggerFactory.getLogger(Encryptor.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String CIPHER_PREFIX = "enc:";

    private final String masterKey;

    public Encryptor(String masterKey) {
        this.masterKey = masterKey;
        if (masterKey == null || masterKey.isBlank()) {
            log.warn("未配置加密主密钥（RA_MASTER_KEY / app.encryption.master-key），敏感设置将以明文存储");
        }
    }

    /**
     * 判断该 key 是否需要加密存储。
     */
    public boolean shouldEncrypt(String keyName) {
        if (keyName == null) return false;
        String lower = keyName.toLowerCase();
        return lower.endsWith("api_key");
    }

    /**
     * 如果已配置主密钥且值是明文，则加密；否则原样返回。
     */
    public String encryptIfNeeded(String keyName, String value) {
        if (!shouldEncrypt(keyName)) return value;
        if (value == null || value.isBlank()) return value;
        if (value.startsWith(CIPHER_PREFIX)) return value;
        return encrypt(value);
    }

    /**
     * 如果值是密文则解密，否则原样返回。
     */
    public String decryptIfNeeded(String value) {
        if (value == null || !value.startsWith(CIPHER_PREFIX)) return value;
        return decrypt(value.substring(CIPHER_PREFIX.length()));
    }

    private String encrypt(String plaintext) {
        if (!isReady()) return plaintext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), spec);
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return CIPHER_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("加密失败", e);
            return plaintext;
        }
    }

    private String decrypt(String base64) {
        if (!isReady()) {
            log.warn("未配置主密钥，无法解密密文");
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(base64);
            if (combined.length < GCM_IV_LENGTH) {
                log.warn("密文格式不正确");
                return null;
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherBytes = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec);
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败（可能主密钥不正确或密文被篡改）", e);
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
