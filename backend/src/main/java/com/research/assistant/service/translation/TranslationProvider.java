package com.research.assistant.service.translation;

import java.util.List;

public interface TranslationProvider {
    String id();

    boolean configured();

    List<ProviderTranslation> translate(
            List<String> texts,
            TranslationLanguage sourceLanguage,
            TranslationLanguage targetLanguage);

    record ProviderTranslation(String text, String detectedSourceLanguage) {
    }
}
