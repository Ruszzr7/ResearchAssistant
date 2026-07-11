package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.GoldenEvalMetrics;
import com.research.assistant.service.ai.PaperAnalysisQualityGate;
import com.research.assistant.service.ai.PaperAnalysisResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 本地 Golden Eval 运行器。只运行确定性的质量门禁，不调用真实模型，避免测试产生费用。
 */
@Service
public class GoldenEvalService {

    private static final String RESOURCE = "eval/paper-analysis-golden.json";

    private final ObjectMapper objectMapper;
    private final PaperAnalysisQualityGate qualityGate;

    public GoldenEvalService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.qualityGate = new PaperAnalysisQualityGate();
    }

    public GoldenEvalMetrics evaluate() {
        GoldenEvalMetrics metrics = new GoldenEvalMetrics();
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            for (JsonNode fixture : objectMapper.readTree(input)) {
                metrics.setTotalCases(metrics.getTotalCases() + 1);
                evaluateFixture(fixture, metrics);
            }
        } catch (Exception e) {
            metrics.getIssueCounts().merge("resource_load_error", 1, Integer::sum);
        }
        if (metrics.getTotalCases() > 0) {
            metrics.setValidRate((double) metrics.getValidCases() / metrics.getTotalCases());
            metrics.setRepairRate((double) metrics.getRepairedCases() / metrics.getTotalCases());
        }
        return metrics;
    }

    private void evaluateFixture(JsonNode fixture, GoldenEvalMetrics metrics) {
        try {
            PaperAnalysisResult result = objectMapper.treeToValue(
                    fixture.path("input"), PaperAnalysisResult.class);
            PaperAnalysisQualityGate.QualityReport report = qualityGate.validateAndRepair(
                    result, fixture.path("topic").asText(""));
            if (report.valid()) {
                metrics.setValidCases(metrics.getValidCases() + 1);
            } else {
                metrics.setInvalidCases(metrics.getInvalidCases() + 1);
            }
            if (report.repaired()) {
                metrics.setRepairedCases(metrics.getRepairedCases() + 1);
            }
            for (String issue : report.issues()) {
                String category = issue.contains("blank") ? "critical_blank" : issue.split(" ", 2)[0];
                metrics.getIssueCounts().merge(category, 1, Integer::sum);
            }
            JsonNode expected = fixture.path("expected");
            if (expected.has("valid") && expected.path("valid").asBoolean() != report.valid()) {
                metrics.setExpectationMismatches(metrics.getExpectationMismatches() + 1);
            }
            if (expected.has("repaired") && expected.path("repaired").asBoolean() != report.repaired()) {
                metrics.setExpectationMismatches(metrics.getExpectationMismatches() + 1);
            }
        } catch (Exception e) {
            metrics.setInvalidCases(metrics.getInvalidCases() + 1);
            metrics.getIssueCounts().merge("fixture_parse_error", 1, Integer::sum);
        }
    }
}
