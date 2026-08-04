package com.research.assistant.service.workbench;

import java.util.List;

/** One user-visible answer unit with an explicit epistemic basis and direct evidence bindings. */
public record WorkbenchAnswerBlock(String text,
                                   Basis basis,
                                   List<Citation> citations,
                                   List<String> requirementIds) {
    public WorkbenchAnswerBlock {
        text = text == null ? "" : text.trim();
        basis = basis == null ? Basis.PAPER_FACT : basis;
        citations = citations == null ? List.of() : citations.stream()
                .filter(item -> item != null && !item.evidenceId().isBlank()).distinct().toList();
        requirementIds = requirementIds == null ? List.of() : requirementIds.stream()
                .filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
    }

    public WorkbenchAnswerBlock(String text, Basis basis, List<Citation> citations) {
        this(text, basis, citations, List.of());
    }

    public boolean requiresPaperEvidence() {
        return basis == Basis.PAPER_FACT || basis == Basis.INFERENCE;
    }

    public enum Basis {
        PAPER_FACT,
        INFERENCE,
        GENERAL_KNOWLEDGE,
        EVIDENCE_LIMIT
    }

    public record Citation(String evidenceId, String quote) {
        public Citation {
            evidenceId = evidenceId == null ? "" : evidenceId.trim();
            quote = quote == null ? "" : quote.trim();
        }
    }
}
