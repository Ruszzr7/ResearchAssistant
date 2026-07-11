package com.research.assistant.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** AI 质量事件汇总，供后续管理页面和评测接口复用。 */
@Data
public class AiQualitySummary {
    private int days;
    private long totalEvents;
    private long passedEvents;
    private long repairedEvents;
    private long fallbackEvents;
    private long failedEvents;
    private double averageLatencyMs;
    private long totalTokens;
    private Map<String, Long> byStatus = new LinkedHashMap<>();
}
