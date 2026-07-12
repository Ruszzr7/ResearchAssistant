package com.research.assistant.dto;

import jakarta.validation.constraints.NotNull;

public class WorkflowPaperImportRequest {
    @NotNull(message = "paperId 不能为空")
    private Long paperId;

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
}
