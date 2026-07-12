package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class WorkflowGapResearchRequest {
    @NotEmpty(message = "至少选择三篇论文")
    @Size(min = 3, max = 50, message = "Gap Research 论文数量必须在 3-50 篇之间")
    private List<Long> paperIds;

    public List<Long> getPaperIds() { return paperIds; }
    public void setPaperIds(List<Long> paperIds) { this.paperIds = paperIds; }
}
