package com.research.assistant.dto.workbench;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FormulaRegionConfirmRequest(
        @Size(max = 4000) String latex,
        @Size(max = 8) List<@NotBlank @Size(max = 4000) String> formulas) {

    @AssertTrue(message = "LaTeX 不能为空")
    public boolean isContentPresent() {
        return latex != null && !latex.isBlank()
                || formulas != null && formulas.stream().anyMatch(
                value -> value != null && !value.isBlank());
    }

    public List<String> resolvedFormulas() {
        if (formulas != null && !formulas.isEmpty()) return formulas;
        return latex == null || latex.isBlank() ? List.of() : List.of(latex);
    }
}
