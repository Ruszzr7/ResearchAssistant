package com.research.assistant.dto;

import lombok.Data;

/** 质量事件状态聚合行。 */
@Data
public class AiQualityStatusCount {
    private String status;
    private Long eventCount;
    private Double averageLatencyMs;
    private Long totalTokens;
}
