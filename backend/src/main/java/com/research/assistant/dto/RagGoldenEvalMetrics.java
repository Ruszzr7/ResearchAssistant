package com.research.assistant.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 不调用真实模型的确定性 RAG Golden Eval 指标。 */
@Data
public class RagGoldenEvalMetrics {
    private int totalCases;
    private int passedCases;
    private int failedCases;
    private double recallAt5;
    private double meanReciprocalRank;
    private double groundedRate;
    private Map<String, Integer> issueCounts = new LinkedHashMap<>();
}
