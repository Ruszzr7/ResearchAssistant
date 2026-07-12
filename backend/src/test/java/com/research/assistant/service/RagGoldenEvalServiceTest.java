package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.RagGoldenEvalMetrics;
import com.research.assistant.service.rag.EvidenceValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagGoldenEvalServiceTest {

    @Test
    void shouldEvaluateDeterministicRagFixtureWithoutModelCalls() {
        RagGoldenEvalMetrics metrics = new RagGoldenEvalService(
                new ObjectMapper(), new EvidenceValidator()).evaluate();

        assertEquals(2, metrics.getTotalCases());
        assertEquals(2, metrics.getPassedCases());
        assertEquals(0, metrics.getFailedCases());
        assertTrue(metrics.getRecallAt5() >= 1.0);
        assertTrue(metrics.getGroundedRate() >= 1.0);
    }
}
