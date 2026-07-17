package com.research.assistant.dto.research;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResearchSessionCreateRequest(
        @NotEmpty @Size(max = 8) List<@Positive Long> paperIds,
        @Positive Long primaryPaperId,
        @Size(max = 255) String title,
        @Size(max = 48) String mode,
        @Positive Integer lastPage,
        @Size(max = 8) String outputLanguage
) { }
