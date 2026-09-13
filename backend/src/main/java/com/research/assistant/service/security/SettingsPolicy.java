package com.research.assistant.service.security;

import com.research.assistant.entity.Settings;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Central policy for user-editable settings.  Keeping this policy in one place
 * prevents a new settings endpoint from accidentally exposing or accepting a
 * different set of keys.
 */
public final class SettingsPolicy {

    public static final int MAX_SETTINGS = 64;
    public static final int MAX_KEY_LENGTH = 100;
    public static final int MAX_VALUE_LENGTH = 2000;
    private static final String MASK = "****";
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u001f\\u007f]");

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "ai_provider", "ai_channel", "api_key", "base_url", "model",
            "translation_provider", "deepl_auth_key", "deepl_api_base_url"
    );

    private static final Set<String> URL_KEYS = Set.of(
            "base_url", "deepl_api_base_url");

    private SettingsPolicy() {
    }

    public static Set<String> allowedKeys() {
        return Collections.unmodifiableSet(ALLOWED_KEYS);
    }

    public static boolean isSensitive(String keyName) {
        if (keyName == null) return false;
        String key = keyName.toLowerCase(Locale.ROOT);
        // 兼容旧库中的 camelCase 设置名（例如 apiKey），避免历史密钥明文返回。
        return key.contains("api_key")
                || "apikey".equals(key)
                || key.endsWith("_auth_key")
                || key.endsWith("_token")
                || key.endsWith("_secret")
                || key.endsWith("_password");
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() <= 8) return MASK;
        int prefixLength = Math.min(6, value.length() - 4);
        return value.substring(0, prefixLength) + MASK + value.substring(value.length() - 4);
    }

    public static boolean isMaskedValue(String value) {
        return value != null && value.contains(MASK);
    }

    public static void validateBatch(List<Settings> settings) {
        if (settings == null) {
            throw new IllegalArgumentException("设置列表不能为空");
        }
        if (settings.size() > MAX_SETTINGS) {
            throw new IllegalArgumentException("一次最多保存 " + MAX_SETTINGS + " 项设置");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Settings setting : settings) {
            if (setting == null || setting.getKeyName() == null || setting.getKeyName().isBlank()) {
                throw new IllegalArgumentException("设置名称不能为空");
            }
            String key = setting.getKeyName().trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("不支持的设置项: " + key);
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("设置项重复: " + key);
            }
            setting.setKeyName(key);
            String value = setting.getValue();
            if (value != null && value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("设置值过长: " + key);
            }
            validateValue(key, value);
        }
    }

    private static void validateValue(String key, String value) {
        if (value == null || value.isBlank()) return;
        if (CONTROL_CHARS.matcher(value).find()) {
            throw new IllegalArgumentException("设置值包含非法控制字符: " + key);
        }
        if ("translation_provider".equals(key) && !"deepl".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("不支持的翻译服务: " + value);
        }
        if ("ai_provider".equals(key)
                && !Set.of("kimi", "deepseek", "glm", "minimax", "mimo", "openai")
                .contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的 AI 供应商: " + value);
        }
        if ("ai_channel".equals(key)
                && !Set.of("default", "coding", "platform", "standard", "payg", "token_plan")
                .contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的 AI 接入通道: " + value);
        }
        if (URL_KEYS.contains(key)) {
            try {
                URI uri = URI.create(value);
                if (!Set.of("http", "https").contains(uri.getScheme())) {
                    throw new IllegalArgumentException("URL 只允许使用 http 或 https: " + key);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("URL 格式无效: " + key);
            }
        }
    }
}
