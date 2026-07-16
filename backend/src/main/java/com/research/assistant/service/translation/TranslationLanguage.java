package com.research.assistant.service.translation;

import java.util.Locale;

public enum TranslationLanguage {
    AUTO("", "AUTO"),
    CHINESE("ZH-HANS", "ZH"),
    ENGLISH("EN-US", "EN");

    private final String deepLTargetCode;
    private final String responseCode;

    TranslationLanguage(String deepLTargetCode, String responseCode) {
        this.deepLTargetCode = deepLTargetCode;
        this.responseCode = responseCode;
    }

    public String deepLTargetCode() {
        return deepLTargetCode;
    }

    public String deepLSourceCode() {
        return this == CHINESE ? "ZH" : this == ENGLISH ? "EN" : "";
    }

    public String responseCode() {
        return responseCode;
    }

    public static TranslationLanguage source(String value) {
        if (value == null || value.isBlank() || "AUTO".equalsIgnoreCase(value)) return AUTO;
        return parse(value, true);
    }

    public static TranslationLanguage target(String value) {
        TranslationLanguage language = parse(value, false);
        if (language == AUTO) throw new IllegalArgumentException("targetLanguage cannot be AUTO");
        return language;
    }

    public static String normalizeDetected(String value) {
        if (value == null || value.isBlank()) return "AUTO";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ZH")) return "ZH";
        if (normalized.startsWith("EN")) return "EN";
        return normalized;
    }

    private static TranslationLanguage parse(String value, boolean source) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ZH") || "CHINESE".equals(normalized)) return CHINESE;
        if (normalized.startsWith("EN") || "ENGLISH".equals(normalized)) return ENGLISH;
        if (source && "AUTO".equals(normalized)) return AUTO;
        throw new IllegalArgumentException("unsupported translation language");
    }
}
