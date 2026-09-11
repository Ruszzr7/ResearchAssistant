package com.research.assistant.dto.research;

import lombok.Data;

import java.util.List;

/** 分页返回研究档案，避免历史会话超过单次列表上限后不可访问。 */
@Data
public class ResearchSessionPage {

    private List<ResearchSessionSummary> records = List.of();
    private long total;
    private int current;
    private int size;
    private int pages;

    public ResearchSessionPage() {
    }

    public ResearchSessionPage(List<ResearchSessionSummary> records,
                               long total, int current, int size, int pages) {
        this.records = records == null ? List.of() : List.copyOf(records);
        this.total = total;
        this.current = current;
        this.size = size;
        this.pages = pages;
    }
}
