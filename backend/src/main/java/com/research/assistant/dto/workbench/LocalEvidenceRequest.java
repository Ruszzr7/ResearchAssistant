package com.research.assistant.dto.workbench;

import com.research.assistant.service.pdf.layout.SelectionAnchor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LocalEvidenceRequest(
        @NotNull SelectionAnchor anchor,
        @Size(max = 2000) String query,
        @Min(1) @Max(20) Integer maxResults) {

    public int safeMaxResults() {
        return maxResults == null ? 8 : maxResults;
    }
}
