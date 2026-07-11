package com.research.assistant.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 质量事件分页筛选条件。 */
@Data
public class AiQualityEventQuery {
    private Integer page = 1;
    private Integer pageSize = 20;
    private Long paperId;
    /** 终态过滤：PASS / REPAIRED / FALLBACK / REJECTED。 */
    private String status;
    private String stage;
    private LocalDateTime from;
    private LocalDateTime to;
}
