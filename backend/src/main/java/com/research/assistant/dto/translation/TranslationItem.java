package com.research.assistant.dto.translation;

public record TranslationItem(String text, String detectedSourceLanguage, boolean cached) {
}
