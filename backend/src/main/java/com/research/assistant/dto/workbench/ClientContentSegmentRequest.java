package com.research.assistant.dto.workbench;

import com.research.assistant.service.pdf.layout.ClientContentSegment;
import com.research.assistant.service.pdf.layout.ClientContentSegmentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ClientContentSegmentRequest(ClientContentSegmentType type,
                                          @Min(0) @Max(10_000_000) int charStart,
                                          @Min(0) @Max(10_000_000) int charEnd,
                                          @Size(max = 1200) String text,
                                          @Size(max = 8) List<@Size(max = 128) String> fonts,
                                          @Valid SelectionBoxRequest rect) {
    public ClientContentSegment toModel() {
        return new ClientContentSegment(type, charStart, charEnd, text, fonts,
                rect == null ? null : rect.toBoundingBox());
    }
}
