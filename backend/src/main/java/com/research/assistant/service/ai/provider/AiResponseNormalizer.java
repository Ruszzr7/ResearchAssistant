package com.research.assistant.service.ai.provider;

import java.util.regex.Pattern;

/** Converts provider-visible answer fields into product-visible final content. */
public final class AiResponseNormalizer {

    private static final Pattern THINK_BLOCK = Pattern.compile(
            "(?is)^\\s*<think>.*?</think>\\s*");

    private AiResponseNormalizer() {
    }

    public static String finalContent(AiProvider provider, String content) {
        String safe = content == null ? "" : content;
        if (provider == AiProvider.MINIMAX) {
            return THINK_BLOCK.matcher(safe).replaceFirst("");
        }
        return safe;
    }
}
