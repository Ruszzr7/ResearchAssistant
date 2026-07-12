package com.research.assistant.dto;

import jakarta.validation.constraints.Size;

public class AgentFolderSuggestRequest {
    private Long paperId;
    @Size(max = 1000, message = "论文标题长度不能超过 1000")
    private String title;

    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
