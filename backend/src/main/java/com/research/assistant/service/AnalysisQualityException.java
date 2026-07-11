package com.research.assistant.service;

import java.util.List;

/** 结构化分析经过所有可用路径后仍不满足质量门禁时抛出。 */
public class AnalysisQualityException extends RuntimeException {

    private final Long paperId;
    private final List<String> issues;

    public AnalysisQualityException(Long paperId, List<String> issues) {
        super("Paper " + paperId + " analysis quality gate rejected: "
                + String.join("; ", issues == null ? List.of() : issues));
        this.paperId = paperId;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public Long getPaperId() {
        return paperId;
    }

    public List<String> getIssues() {
        return issues;
    }
}
