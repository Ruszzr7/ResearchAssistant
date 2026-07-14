package com.research.assistant.dto;

import jakarta.validation.constraints.Size;

public class AgentFolderSuggestRequest {
    private Long paperId;
    @Size(max = 1000, message = "论文标题长度不能超过 1000")
    private String title;
    @Size(max = 10000, message = "论文摘要长度不能超过 10000")
    private String abstractText;

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAbstractText() { return abstractText; }
    public void setAbstractText(String abstractText) { this.abstractText = abstractText; }
}
