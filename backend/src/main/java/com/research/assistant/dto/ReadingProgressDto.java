package com.research.assistant.dto;

import lombok.Data;

/**
 * 论文阅读进度视图。
 */
@Data
public class ReadingProgressDto {

    private Long paperId;

    /** PDF 总页数 */
    private Integer pageCount;

    /** 当前读到第几页 */
    private Integer currentPage;

    /** 累计阅读时长（秒） */
    private Integer readSeconds;

    /** 阅读进度百分比（0–100） */
    private Integer progressPercent;

    /** 当前阅读状态 */
    private String readingStatus;
}
