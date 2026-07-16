package com.research.assistant.dto.translation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Text segments are independent and returned in the same order. */
public record TranslationRequest(
        @NotEmpty @Size(max = 50)
        List<@NotBlank @Size(max = 30_000) String> texts,
        @Size(max = 12) String sourceLanguage,
        @NotBlank @Size(max = 12) String targetLanguage) {
}
