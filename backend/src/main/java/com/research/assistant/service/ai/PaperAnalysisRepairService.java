package com.research.assistant.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;

import java.util.List;

/**
 * 论文精读结构化结果的一次性 LLM 修复器。
 *
 * <p>修复器只负责一次“按错误清单重排 JSON”的调用，结果仍必须重新经过
 * {@link PaperAnalysisQualityGate}，不能绕过质量门禁。</p>
 */
public final class PaperAnalysisRepairService {

    private static final String REPAIR_SYSTEM_PROMPT = """
            You repair a structured paper-analysis JSON object.
            Return JSON only, using the same camelCase field names as the input object.
            Keep all factual content from the input; do not invent paper facts.
            Normalize missing arrays to [], keep relevanceScore null when no research topic exists.
            """;

    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final PaperAnalysisQualityGate qualityGate;
    private final LlmCallPolicy policy;

    public PaperAnalysisRepairService(LLMService llmService, ObjectMapper objectMapper,
                                      PaperAnalysisQualityGate qualityGate) {
        this(llmService, objectMapper, qualityGate, LlmCallPolicy.PAPER_ANALYSIS_REPAIR);
    }

    PaperAnalysisRepairService(LLMService llmService, ObjectMapper objectMapper,
                                PaperAnalysisQualityGate qualityGate, LlmCallPolicy policy) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.qualityGate = qualityGate;
        this.policy = policy;
    }

    public RepairAttempt repair(PaperAnalysisResult original,
                                PaperAnalysisQualityGate.QualityReport initialQuality,
                                String researchTopic) {
        if (original == null || initialQuality == null || initialQuality.valid()) {
            return RepairAttempt.notAttempted(initialQuality);
        }

        final String originalJson;
        try {
            originalJson = objectMapper.writeValueAsString(original);
        } catch (Exception e) {
            return RepairAttempt.failed("cannot serialize invalid analysis: " + e.getMessage());
        }

        String userMessage = buildRepairMessage(originalJson, initialQuality, researchTopic);
        if (policy.exceedsInputBudget(REPAIR_SYSTEM_PROMPT, userMessage)) {
            return RepairAttempt.failed("repair prompt exceeded input token budget");
        }
        LlmResponse response;
        try {
            response = llmService.chatWithUsage(REPAIR_SYSTEM_PROMPT, userMessage);
        } catch (Exception e) {
            return RepairAttempt.failed("repair call failed: " + e.getMessage());
        }
        if (policy.exceedsOutputBudget(response)) {
            return RepairAttempt.failed("repair output exceeded token/character budget", response);
        }

        try {
            String output = response.getContent() == null ? "" : response.getContent();
            JsonNode root = objectMapper.readTree(JsonUtils.extractJson(output));
            PaperAnalysisResult repaired = objectMapper.treeToValue(root, PaperAnalysisResult.class);
            PaperAnalysisQualityGate.QualityReport quality =
                    qualityGate.validateAndRepair(repaired, researchTopic);
            return new RepairAttempt(repaired, quality, response, 1, null);
        } catch (Exception e) {
            return RepairAttempt.failed("repair JSON parse failed: " + e.getMessage(), response);
        }
    }

    private String buildRepairMessage(String originalJson,
                                      PaperAnalysisQualityGate.QualityReport quality,
                                      String researchTopic) {
        String boundedJson = policy.limitInput(originalJson);
        return "Research topic: " + (researchTopic == null ? "" : researchTopic)
                + "\nValidation issues: " + String.join("; ", quality.issues())
                + "\nInput JSON:\n" + boundedJson;
    }

    public record RepairAttempt(PaperAnalysisResult result,
                                PaperAnalysisQualityGate.QualityReport quality,
                                LlmResponse response,
                                int attempts,
                                String error) {

        static RepairAttempt notAttempted(PaperAnalysisQualityGate.QualityReport quality) {
            return new RepairAttempt(null, quality, null, 0, "repair not required");
        }

        static RepairAttempt failed(String error) {
            return failed(error, LlmResponse.of(""));
        }

        static RepairAttempt failed(String error, LlmResponse response) {
            return new RepairAttempt(null,
                    PaperAnalysisQualityGate.QualityReport.invalid(List.of(error)),
                    response, 1, error);
        }

        public boolean succeeded() {
            return result != null && quality != null && quality.valid();
        }
    }
}
