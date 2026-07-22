package com.research.assistant.dto.workbench;

import com.research.assistant.service.pdf.layout.ClientTextRange;
import jakarta.validation.constraints.Min;

public record ClientTextRangeRequest(@Min(0) int itemIndex,
                                     @Min(0) int spanIndex,
                                     @Min(0) int startOffset,
                                     @Min(0) int endOffset) {

    public ClientTextRange toModel() {
        if (endOffset < startOffset) {
            throw new IllegalArgumentException("invalid client text range");
        }
        return new ClientTextRange(itemIndex, spanIndex, startOffset, endOffset);
    }
}
