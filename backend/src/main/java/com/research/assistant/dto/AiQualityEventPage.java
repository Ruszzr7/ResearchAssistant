package com.research.assistant.dto;

import com.research.assistant.entity.AiQualityEvent;
import lombok.Data;

import java.util.List;

/** 质量事件分页结果。 */
@Data
public class AiQualityEventPage {
    private List<AiQualityEvent> items;
    private int page;
    private int pageSize;
    private long total;
    private long totalPages;
}
