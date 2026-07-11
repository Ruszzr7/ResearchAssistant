package com.research.assistant.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Golden Eval 的结构化质量指标。 */
@Data
public class GoldenEvalMetrics {
    private int totalCases;
    private int validCases;
    private int repairedCases;
    private int invalidCases;
    private int expectationMismatches;
    private double validRate;
    private double repairRate;
    private Map<String, Integer> issueCounts = new LinkedHashMap<>();
}
