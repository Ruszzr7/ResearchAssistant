package com.research.assistant.dto.workbench;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FormulaRegionConfirmRequest(
        @NotBlank @Size(max = 4000) String latex) {
}
