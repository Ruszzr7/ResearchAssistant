package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class BibTeXExportRequest {
    @NotEmpty(message = "至少选择一篇论文")
    @Size(max = 500, message = "一次最多导出 500 篇论文")
    private List<Long> ids;

    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }
}
