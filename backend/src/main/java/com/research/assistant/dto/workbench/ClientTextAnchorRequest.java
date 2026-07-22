package com.research.assistant.dto.workbench;

import com.research.assistant.service.pdf.layout.ClientTextAnchor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ClientTextAnchorRequest(@Min(1) int version,
                                      @Min(1) @Max(100000) int page,
                                      @Size(max = 256) String documentFingerprint,
                                      @Min(1) int textMapVersion,
                                      @NotEmpty @Size(max = 100) List<@Valid ClientTextRangeRequest> ranges) {

    public ClientTextAnchor toModel() {
        return new ClientTextAnchor(version, page, documentFingerprint, textMapVersion,
                ranges.stream().map(ClientTextRangeRequest::toModel).toList());
    }
}
