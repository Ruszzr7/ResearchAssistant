package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.ResearchSynthesisGoldenEvalMetrics;
import com.research.assistant.service.ai.ResearchSynthesisQualityGate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/** Local, model-free evaluation for compare-papers and analyze-gaps output contracts. */
@Service
public class ResearchSynthesisGoldenEvalService {

    private static final String RESOURCE = "eval/synthesis-golden.json";

    private final ObjectMapper objectMapper;
    private final ResearchSynthesisQualityGate qualityGate;

    public ResearchSynthesisGoldenEvalService(ObjectMapper objectMapper,
                                              ResearchSynthesisQualityGate qualityGate) {
        this.objectMapper = objectMapper;
        this.qualityGate = qualityGate;
    }

    public ResearchSynthesisGoldenEvalMetrics evaluate() {
        ResearchSynthesisGoldenEvalMetrics metrics = new ResearchSynthesisGoldenEvalMetrics();
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            for (JsonNode fixture : objectMapper.readTree(input)) {
                metrics.setTotalCases(metrics.getTotalCases() + 1);
                String type = fixture.path("type").asText("");
                if ("compare".equalsIgnoreCase(type)) {
                    metrics.setCompareCases(metrics.getCompareCases() + 1);
                } else if ("gap".equalsIgnoreCase(type)) {
                    metrics.setGapCases(metrics.getGapCases() + 1);
                }
                ResearchSynthesisQualityGate.QualityReport actual = "compare".equalsIgnoreCase(type)
                        ? qualityGate.validateCompare(fixture.path("content").asText(""),
                        fixture.path("paperCount").asInt(2))
                        : qualityGate.validateGaps(fixture.path("content").asText(""));
                boolean expected = fixture.path("expectedValid").asBoolean(false);
                if (actual.valid() == expected) {
                    metrics.setPassedCases(metrics.getPassedCases() + 1);
                } else {
                    metrics.setFailedCases(metrics.getFailedCases() + 1);
                    String issue = actual.issues().isEmpty() ? "expectation_mismatch" : actual.issues().get(0);
                    metrics.getIssueCounts().merge(issue, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            metrics.setFailedCases(metrics.getFailedCases() + 1);
            metrics.getIssueCounts().merge("resource_load_error", 1, Integer::sum);
        }
        if (metrics.getTotalCases() > 0) {
            metrics.setPassRate((double) metrics.getPassedCases() / metrics.getTotalCases());
        }
        return metrics;
    }
}
