package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CitationCheckRequest {
    @NotBlank(message = "段落不能为空")
    @Size(max = 12000, message = "段落长度不能超过 12000")
    private String paragraph;
    @NotEmpty(message = "至少需要一篇参考论文")
    @Size(max = 50, message = "论文数量不能超过 50")
    private List<Long> paperIds;

    public String getParagraph() { return paragraph; }
    public void setParagraph(String paragraph) { this.paragraph = paragraph; }
    public List<Long> getPaperIds() { return paperIds; }
    public void setPaperIds(List<Long> paperIds) { this.paperIds = paperIds; }
}
