package com.research.assistant.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PaperAnalysisQualityGateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaperAnalysisQualityGate gate = new PaperAnalysisQualityGate();

    @Test
    void goldenFixturesShouldRemainStable() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/eval/paper-analysis-golden.json")) {
            assertThat(input).as("golden evaluation resource").isNotNull();
            for (JsonNode fixture : objectMapper.readTree(input)) {
                PaperAnalysisResult result = objectMapper.treeToValue(
                        fixture.path("input"), PaperAnalysisResult.class);
                JsonNode expected = fixture.path("expected");

                PaperAnalysisQualityGate.QualityReport report = gate.validateAndRepair(
                        result, fixture.path("topic").asText(""));

                assertThat(report.valid()).as(fixture.path("name").asText()).isEqualTo(expected.path("valid").asBoolean());
                assertThat(report.repaired()).as(fixture.path("name").asText()).isEqualTo(expected.path("repaired").asBoolean());
                if (expected.has("normalizedMethodType")) {
                    assertThat(result.getMethodType()).isEqualTo(expected.path("normalizedMethodType").asText());
                }
                if (expected.has("normalizedScore")) {
                    assertThat(result.getRelevanceScore()).isEqualTo(expected.path("normalizedScore").asInt());
                }
            }
        }
    }

    @Test
    void shouldClearRelevanceWhenTopicIsMissing() {
        PaperAnalysisResult result = minimalValidResult();
        result.setRelevanceScore(7);
        result.setRelevanceReason("should be removed");

        PaperAnalysisQualityGate.QualityReport report = gate.validateAndRepair(result, " ");

        assertThat(report.valid()).isTrue();
        assertThat(report.repaired()).isTrue();
        assertThat(result.getRelevanceScore()).isNull();
        assertThat(result.getRelevanceReason()).isNull();
    }

    @Test
    void shouldNormalizeFallbackJsonShape() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {
                  "core_contribution": " contribution ",
                  "method_type": "experimental",
                  "method_summary": " summary ",
                  "datasets": "not-an-array",
                  "relevance_score": 12
                }
                """);

        PaperAnalysisQualityGate.QualityReport report =
                gate.validateAndRepairFallback(root, "model compression");

        assertThat(report.valid()).isTrue();
        assertThat(report.repaired()).isTrue();
        assertThat(root.path("method_type").asText()).isEqualTo("EXPERIMENTAL");
        assertThat(root.path("datasets").isArray()).isTrue();
        assertThat(root.path("relevance_score").asInt()).isEqualTo(10);
    }

    private PaperAnalysisResult minimalValidResult() {
        PaperAnalysisResult result = new PaperAnalysisResult();
        result.setDomain("AI");
        result.setCoreContribution("contribution");
        result.setMethodType("SYSTEM");
        result.setMethodSummary("summary");
        return result;
    }
}
