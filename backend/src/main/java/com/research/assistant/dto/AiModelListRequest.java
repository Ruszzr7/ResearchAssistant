package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Transient credentials used only for one model-catalog query. */
public record AiModelListRequest(
        @NotBlank @Size(max = 2_000) String baseUrl,
        @Size(max = 2_000) String apiKey
) {
}
