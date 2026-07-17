package com.research.assistant.dto.research;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResearchMessageInput(
        @NotBlank @Size(max = 100) String messageKey,
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 50_000) String content,
        @Size(max = 36) String runId,
        JsonNode selectionAnchor,
        JsonNode evidence
) { }
