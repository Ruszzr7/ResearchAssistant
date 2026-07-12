package com.research.assistant.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic Golden Eval metrics for compare and gap synthesis reports. */
@Data
public class ResearchSynthesisGoldenEvalMetrics {
    private int totalCases;
    private int passedCases;
    private int failedCases;
    private int compareCases;
    private int gapCases;
    private double passRate;
    private Map<String, Integer> issueCounts = new LinkedHashMap<>();
}
