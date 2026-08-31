package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One-shot capability probe settings. The values are deliberately separate
 * from the persisted settings endpoint so testing a draft never saves it.
 */
public record AiCapabilityTestRequest(
        @NotBlank @Size(max = 2_000) String baseUrl,
        @NotBlank @Size(max = 200) String model,
        @Size(max = 2_000) String apiKey
) {
    public AiCapabilityTestRequest {
        baseUrl = baseUrl == null ? null : baseUrl.trim();
        model = model == null ? null : model.trim();
        apiKey = apiKey == null ? null : apiKey.trim();
    }
}
