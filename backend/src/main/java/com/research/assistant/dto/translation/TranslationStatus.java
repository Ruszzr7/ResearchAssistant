package com.research.assistant.dto.translation;

import java.util.List;

public record TranslationStatus(String provider, boolean configured, List<String> targetLanguages) {
}
