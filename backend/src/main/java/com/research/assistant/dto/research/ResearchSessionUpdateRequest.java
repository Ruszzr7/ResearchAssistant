package com.research.assistant.dto.research;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResearchSessionUpdateRequest(
        @Size(max = 255) String title,
        @Positive Integer lastPage,
        @Size(max = 48) String mode,
        @Size(max = 8) String outputLanguage,
        Boolean archived,
        @Size(max = 8) List<@Positive Long> paperIds
) { }
