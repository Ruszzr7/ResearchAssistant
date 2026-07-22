package com.research.assistant.dto.workbench;

import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SelectionAnchorRequest(
        @Min(1) @Max(100000) int page,
        @NotEmpty @Size(max = 100) List<@Valid SelectionBoxRequest> boxes,
        @Size(max = 8000) String anchorText,
        SelectionAnchorKind preferredKind,
        @Valid ClientTextAnchorRequest clientTextAnchor) {
}
