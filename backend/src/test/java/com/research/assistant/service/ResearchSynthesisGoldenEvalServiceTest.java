package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.ResearchSynthesisGoldenEvalMetrics;
import com.research.assistant.service.ai.ResearchSynthesisQualityGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSynthesisGoldenEvalServiceTest {

    @Test
    void shouldEvaluateCompareAndGapFixturesWithoutModelCalls() {
        ResearchSynthesisGoldenEvalMetrics metrics = new ResearchSynthesisGoldenEvalService(
                new ObjectMapper(), new ResearchSynthesisQualityGate()).evaluate();

        assertEquals(4, metrics.getTotalCases());
        assertEquals(4, metrics.getPassedCases());
        assertEquals(2, metrics.getCompareCases());
        assertEquals(2, metrics.getGapCases());
        assertTrue(metrics.getPassRate() > 0.99);
    }
}
