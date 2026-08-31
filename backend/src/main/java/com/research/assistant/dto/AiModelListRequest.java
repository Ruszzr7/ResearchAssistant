package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Transient credentials used only for one model-catalog query. */
public record AiModelListRequest(
        @NotBlank @Size(max = 2_000) String baseUrl,
        @Size(max = 2_000) String apiKey,
        @Size(max = 20) String role
) {
    public AiModelListRequest(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, "CHAT");
    }

    public AiModelListRequest {
        role = role == null || role.isBlank() ? "CHAT" : role.trim().toUpperCase();
        if (!java.util.Set.of("CHAT", "DOCUMENT").contains(role)) {
            throw new IllegalArgumentException("模型角色只允许 CHAT 或 DOCUMENT");
        }
    }
}
