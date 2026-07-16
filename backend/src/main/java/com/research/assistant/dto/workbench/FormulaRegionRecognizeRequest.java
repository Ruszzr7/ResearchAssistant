package com.research.assistant.dto.workbench;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record FormulaRegionRecognizeRequest(
        @Min(1) @Max(100000) int page,
        @NotNull @Valid SelectionBoxRequest bbox) {
}
