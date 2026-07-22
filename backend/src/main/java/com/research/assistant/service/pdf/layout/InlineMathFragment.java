package com.research.assistant.service.pdf.layout;

import java.util.List;

/** A suspicious inline-math span retained in the original block text coordinates. */
public record InlineMathFragment(int start,
                                 int end,
                                 String sourceText,
                                 double confidence,
                                 List<String> signals) {

    public InlineMathFragment {
        start = Math.max(0, start);
        end = Math.max(start, end);
        sourceText = sourceText == null ? "" : sourceText;
        confidence = Math.max(0, Math.min(1, confidence));
        signals = signals == null ? List.of() : List.copyOf(signals);
    }
}
