package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.GoldenEvalMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenEvalServiceTest {

    @Test
    void shouldEvaluateVersionedGoldenFixturesWithoutModelCalls() {
        GoldenEvalMetrics metrics = new GoldenEvalService(
                new ObjectMapper()).evaluate();

        assertThat(metrics.getTotalCases()).isEqualTo(21);
        assertThat(metrics.getValidCases()).isEqualTo(20);
        assertThat(metrics.getInvalidCases()).isEqualTo(1);
        assertThat(metrics.getRepairedCases()).isEqualTo(20);
        assertThat(metrics.getExpectationMismatches()).isZero();
        assertThat(metrics.getValidRate()).isEqualTo(20.0 / 21.0);
        assertThat(metrics.getManualScoredCases()).isEqualTo(1);
        assertThat(metrics.getAverageManualScore()).isEqualTo(5.0);
    }
}
