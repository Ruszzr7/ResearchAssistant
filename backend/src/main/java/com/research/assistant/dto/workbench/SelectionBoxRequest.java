package com.research.assistant.dto.workbench;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

public record SelectionBoxRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double x,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double y,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") Double width,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") Double height) {

    public NormalizedBoundingBox toBoundingBox() {
        return new NormalizedBoundingBox(x, y, width, height);
    }

    @AssertTrue(message = "选区范围不能超出页面")
    public boolean isInsidePage() {
        return x == null || y == null || width == null || height == null
                || (x + width <= 1.000001 && y + height <= 1.000001);
    }
}
