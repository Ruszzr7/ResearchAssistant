package com.research.assistant.service.ai.provider;

import java.util.Locale;

/** Explicitly supported model providers. */
public enum AiProvider {
    KIMI,
    DEEPSEEK,
    GLM,
    MINIMAX,
    MIMO,
    OPENAI;

    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AiProvider resolve(String configured, String baseUrl, String model) {
        if (configured != null && !configured.isBlank()) {
            try {
                return valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to legacy inference so existing installations remain usable.
            }
        }
        String base = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (base.contains("kimi.com") || base.contains("moonshot.") || name.contains("kimi")
                || name.equals("k3") || name.startsWith("k3-")) return KIMI;
        if (base.contains("deepseek.") || name.startsWith("deepseek")) return DEEPSEEK;
        if (base.contains("bigmodel.") || base.contains("api.z.ai") || name.startsWith("glm")) return GLM;
        if (base.contains("minimax") || name.startsWith("minimax")) return MINIMAX;
        if (base.contains("xiaomimimo") || name.startsWith("mimo")) return MIMO;
        return OPENAI;
    }
}
