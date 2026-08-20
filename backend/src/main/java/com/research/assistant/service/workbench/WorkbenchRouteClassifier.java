package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.LlmCallPolicy;
import com.research.assistant.service.memory.PaperConversationTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Small, bounded fallback used only when deterministic QA routing is genuinely ambiguous. */
@Component
public class WorkbenchRouteClassifier {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchRouteClassifier.class);
    private static final LlmCallPolicy POLICY = new LlmCallPolicy(
            "workbench-route", 3_000, 900, 180, 1, true, "low");
    private static final String SYSTEM = """
            你只判断科研对话本轮属于哪种问答，不回答问题，不输出思考过程。
            只能返回 JSON：{"route":"PAPER_QA|FOLLOW_UP_QA|GENERAL_CHAT","confidence":0.0,"normalizedIntent":"..."}。
            PAPER_QA：问题依赖当前论文但不承接上一轮；FOLLOW_UP_QA：存在指代、省略、备选、原因或语义上承接上一轮；
            GENERAL_CHAT：与当前论文和上一轮都无关。容忍口语、漏字和常见错别字，但不能把普通常识强行归入论文。
            """;

    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public WorkbenchRouteClassifier(LLMService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    public Optional<Decision> classify(String question,
                                       String paperTitle,
                                       List<PaperConversationTurn> history) {
        try {
            String prompt = objectMapper.writeValueAsString(java.util.Map.of(
                    "question", bounded(question, 400),
                    "paperTitle", bounded(paperTitle, 300),
                    "recentTurns", recentTurns(history)));
            LlmResponse response = llmService.chatWithUsage(SYSTEM, prompt, POLICY);
            String content = response == null ? "" : response.getContent();
            JsonNode root = objectMapper.readTree(JsonUtils.extractJson(content));
            WorkbenchTurnRoute.Type route = WorkbenchTurnRoute.Type.valueOf(
                    root.path("route").asText("").trim().toUpperCase(Locale.ROOT));
            if (route != WorkbenchTurnRoute.Type.PAPER_QA
                    && route != WorkbenchTurnRoute.Type.FOLLOW_UP_QA
                    && route != WorkbenchTurnRoute.Type.GENERAL_CHAT) return Optional.empty();
            double confidence = root.path("confidence").asDouble(0);
            if (confidence < .70) return Optional.empty();
            return Optional.of(new Decision(route, Math.min(1, confidence),
                    bounded(root.path("normalizedIntent").asText(""), 300)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException error) {
            log.info("workbench_route_classifier_fallback errorType={}", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private List<java.util.Map<String, String>> recentTurns(List<PaperConversationTurn> history) {
        if (history == null || history.isEmpty()) return List.of();
        int start = Math.max(0, history.size() - 3);
        return history.subList(start, history.size()).stream().map(turn -> java.util.Map.of(
                "question", bounded(turn.question(), 320),
                "answer", bounded(turn.answer(), 500))).toList();
    }

    private String bounded(String value, int limit) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }

    public record Decision(WorkbenchTurnRoute.Type route, double confidence,
                           String normalizedIntent) { }
}
