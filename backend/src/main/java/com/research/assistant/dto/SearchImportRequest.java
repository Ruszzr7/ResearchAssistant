package com.research.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchImportRequest {
    @NotEmpty(message = "没有要导入的论文")
    @Size(max = 500, message = "单次最多导入 500 篇论文")
    @Valid
    private List<SearchImportPaper> papers;
    private Long folderId;

    public List<SearchImportPaper> getPapers() { return papers; }
    public void setPapers(List<SearchImportPaper> papers) { this.papers = papers; }
    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }
}
