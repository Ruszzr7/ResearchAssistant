package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.RagGoldenEvalMetrics;
import com.research.assistant.service.rag.EvidenceValidator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 本地、确定性的 RAG 评测。使用 fixture 中的候选文本做 lexical baseline，避免调用真实 embedding/LLM。
 */
@Service
public class RagGoldenEvalService {

    private static final String RESOURCE = "eval/rag-golden.json";
    private final ObjectMapper objectMapper;
    private final EvidenceValidator evidenceValidator;

    public RagGoldenEvalService(ObjectMapper objectMapper, EvidenceValidator evidenceValidator) {
        this.objectMapper = objectMapper;
        this.evidenceValidator = evidenceValidator;
    }

    public RagGoldenEvalMetrics evaluate() {
        RagGoldenEvalMetrics metrics = new RagGoldenEvalMetrics();
        double recallSum = 0;
        double reciprocalRankSum = 0;
        int groundedTotal = 0;
        int groundedPassed = 0;
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            for (JsonNode fixture : objectMapper.readTree(input)) {
                metrics.setTotalCases(metrics.getTotalCases() + 1);
                Evaluation evaluation = evaluateCase(fixture);
                recallSum += evaluation.recall;
                reciprocalRankSum += evaluation.reciprocalRank;
                groundedTotal += evaluation.groundedTotal;
                groundedPassed += evaluation.groundedPassed;
                if (evaluation.passed) {
                    metrics.setPassedCases(metrics.getPassedCases() + 1);
                } else {
                    metrics.setFailedCases(metrics.getFailedCases() + 1);
                    metrics.getIssueCounts().merge(evaluation.issue, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            metrics.getIssueCounts().merge("resource_load_error", 1, Integer::sum);
        }
        if (metrics.getTotalCases() > 0) {
            metrics.setRecallAt5(recallSum / metrics.getTotalCases());
            metrics.setMeanReciprocalRank(reciprocalRankSum / metrics.getTotalCases());
        }
        metrics.setGroundedRate(groundedTotal == 0 ? 0 : (double) groundedPassed / groundedTotal);
        return metrics;
    }

    private Evaluation evaluateCase(JsonNode fixture) {
        String query = fixture.path("query").asText("");
        List<JsonNode> candidates = new ArrayList<>();
        fixture.path("candidates").forEach(candidates::add);
        Set<String> expected = new HashSet<>();
        fixture.path("relevantEvidenceIds").forEach(node -> expected.add(node.asText()));
        List<String> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble((JsonNode candidate) -> lexicalScore(query,
                        candidateText(candidate))).reversed())
                .limit(5)
                .map(candidate -> candidate.path("evidenceId").asText())
                .toList();
        long hits = ranked.stream().filter(expected::contains).count();
        double recall = expected.isEmpty() ? (hits == 0 ? 1 : 0) : (double) hits / expected.size();
        double reciprocalRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (expected.contains(ranked.get(i))) {
                reciprocalRank = 1.0 / (i + 1);
                break;
            }
        }

        List<Map<String, Object>> candidateMaps = candidates.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        List<Map<String, Object>> rawEvidence = expected.stream().map(id -> {
            Map<String, Object> evidence = new java.util.LinkedHashMap<>();
            evidence.put("evidenceId", id);
            evidence.put("snippet", candidateMaps.stream()
                    .filter(candidate -> id.equals(String.valueOf(candidate.get("evidenceId"))))
                    .findFirst().map(candidate -> String.valueOf(candidate.get("summary"))).orElse(""));
            return evidence;
        })
                .toList();
        List<Map<String, Object>> verified = evidenceValidator.validate(rawEvidence, candidateMaps);
        int groundedTotal = rawEvidence.size();
        int groundedPassed = verified.size();
        boolean passed = recall >= 1.0 && groundedPassed == groundedTotal;
        return new Evaluation(passed, recall, reciprocalRank, groundedTotal, groundedPassed,
                passed ? "" : (recall < 1.0 ? "recall_miss" : "evidence_not_grounded"));
    }

    private double lexicalScore(String query, String content) {
        Set<String> queryTokens = tokens(query);
        Set<String> contentTokens = tokens(content);
        if (queryTokens.isEmpty()) return 0;
        long overlap = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) overlap / queryTokens.size();
    }

    private String candidateText(JsonNode candidate) {
        String content = candidate.path("content").asText("");
        return content.isBlank() ? candidate.path("summary").asText("") : content;
    }

    private Set<String> tokens(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(normalized.split("\\s+"))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    private record Evaluation(boolean passed, double recall, double reciprocalRank,
                              int groundedTotal, int groundedPassed, String issue) {
    }
}
