package com.research.assistant.dto;

import java.util.List;

/**
 * 引用推荐与冲突检查请求。
 */
public class CitationCheckRequest {

    private String paragraph;
    private List<Long> paperIds;

    public String getParagraph() { return paragraph; }
    public void setParagraph(String paragraph) { this.paragraph = paragraph; }

    public List<Long> getPaperIds() { return paperIds; }
    public void setPaperIds(List<Long> paperIds) { this.paperIds = paperIds; }
}
