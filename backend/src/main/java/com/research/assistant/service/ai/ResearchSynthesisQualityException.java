package com.research.assistant.service.ai;

import java.util.List;

/** Raised when a compare or gap report cannot pass the deterministic quality gate. */
public class ResearchSynthesisQualityException extends RuntimeException {

    private final String operation;
    private final List<String> issues;

    public ResearchSynthesisQualityException(String operation, List<String> issues) {
        super(operation + " report rejected by quality gate: " + String.join(", ", issues));
        this.operation = operation;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public String getOperation() {
        return operation;
    }

    public List<String> getIssues() {
        return issues;
    }
}
