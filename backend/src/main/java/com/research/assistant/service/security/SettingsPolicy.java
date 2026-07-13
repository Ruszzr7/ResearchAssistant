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
            "api_key", "base_url", "model", "research_topic",
            "embedding_api_key", "embedding_base_url", "embedding_model",
            "openalex_enabled", "ieee_xplore_enabled", "ieee_xplore_api_key",
            "acm_dl_enabled", "acm_dl_api_url", "acm_dl_api_key",
            "semantic_scholar_api_key",
            "pdf_parser_provider", "pdf_parser_external_enabled", "pdf_parser_external_command",
            "pdf_js_viewer_enabled", "formula_extractor_enabled", "formula_extractor_command",
            "figure_extractor_enabled", "figure_extractor_command",
            "obsidian_vault_path", "zotero_user_id", "zotero_api_key", "zotero_collection_key",
            "vector_store_provider", "qdrant_host", "qdrant_port", "qdrant_use_tls",
            "qdrant_api_key", "qdrant_collection",
            "rag_enabled", "rag_rerank_enabled", "rag_rerank_top_k", "rag_answer_top_k",
            "rag_rerank_min_chunks"
    );

    private static final Set<String> BOOLEAN_KEYS = Set.of(
            "openalex_enabled", "ieee_xplore_enabled", "acm_dl_enabled",
            "pdf_parser_external_enabled", "pdf_js_viewer_enabled",
            "formula_extractor_enabled", "figure_extractor_enabled",
            "qdrant_use_tls", "rag_enabled", "rag_rerank_enabled"
    );

    private static final Set<String> COMMAND_KEYS = Set.of(
            "pdf_parser_external_command", "formula_extractor_command", "figure_extractor_command"
    );

    private static final Set<String> URL_KEYS = Set.of("base_url", "embedding_base_url", "acm_dl_api_url");

    private static final Set<String> SENSITIVE_EXACT_KEYS = Set.of("zotero_collection_key");

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
                || key.endsWith("_token")
                || key.endsWith("_secret")
                || key.endsWith("_password")
                || SENSITIVE_EXACT_KEYS.contains(key);
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
        if (BOOLEAN_KEYS.contains(key)
                && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("设置项必须是 true 或 false: " + key);
        }
        if ("pdf_parser_provider".equals(key)
                && !Set.of("PDFBOX", "EXTERNAL").contains(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的 PDF 解析器: " + value);
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
        if (COMMAND_KEYS.contains(key) && value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("外部命令包含非法字符: " + key);
        }
    }
}
