package com.research.assistant.dto.translation;

import java.util.List;

public record TranslationResponse(
        String provider,
        String sourceLanguage,
        String targetLanguage,
        List<TranslationItem> items,
        boolean cached) {
}
