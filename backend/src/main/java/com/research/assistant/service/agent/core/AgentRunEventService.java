package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentRunEvent;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.AgentRunMapper;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.service.agent.runtime.AgentRunFailureClassifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rebuildable run events. Polling and SSE expose this same persisted-state projection. */
@Service
public class AgentRunEventService {
    private final AgentLoopService loopService;
    private final AgentToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;
    private final AgentRunMapper runMapper;

    public AgentRunEventService(AgentLoopService loopService, AgentToolCallMapper toolCallMapper,
                                ObjectMapper objectMapper) {
        this(loopService, toolCallMapper, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunEventService(AgentLoopService loopService, AgentToolCallMapper toolCallMapper,
                                ObjectMapper objectMapper, AgentRunMapper runMapper) {
        this.loopService = loopService;
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
        this.runMapper = runMapper;
    }

    public List<AgentRunEvent> events(String runId, long afterId) {
        AgentTurnResult state = loopService.currentResult(runId);
        List<AgentRunEvent> events = new ArrayList<>();
        events.add(new AgentRunEvent(1, "run.status", Map.of("runId", runId, "status", state.status())));
        long id = 1;
        for (AgentToolCallRecord call : toolCallMapper.selectByRunId(runId)) {
            id++;
            String type = switch (call.getStatus()) {
                case "COMPLETED" -> "tool.completed";
                case "FAILED", "CANCELLED" -> "tool.failed";
                default -> "tool.requested";
            };
            events.add(new AgentRunEvent(id, type, toolEventData(call)));
        }
        if (isTerminal(state.status())) {
            Object diagnostics = modelDiagnostics(runId);
            if (diagnostics != null) events.add(new AgentRunEvent(++id, "run.diagnostics", diagnostics));
        }
        if ("WAITING_USER".equals(state.status())) {
            events.add(new AgentRunEvent(++id, "confirmation.required", state));
        } else if ("WAITING_CLIENT".equals(state.status())) {
            events.add(new AgentRunEvent(++id, "action.required", state));
        } else if ("COMPLETED".equals(state.status())) {
            events.add(new AgentRunEvent(++id, "message.final", state));
        } else if ("FAILED".equals(state.status()) || "CANCELLED".equals(state.status())) {
            events.add(new AgentRunEvent(++id, "run.failed", failedEventData(runId, state)));
        }
        return events.stream().filter(event -> event.id() > afterId).toList();
    }

    private Object failedEventData(String runId, AgentTurnResult state) {
        if (runMapper == null) return state;
        AgentRunRecord run = runMapper.selectByRunId(runId);
        if (run == null) return state;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId);
        data.put("status", state.status());
        data.put("message", state.message());
        if (run.getErrorCode() != null) {
            data.put("errorCode", run.getErrorCode());
            AgentRunFailureClassifier.Failure failure = AgentRunFailureClassifier.fromCode(run.getErrorCode());
            if ("RUN_TIMEOUT".equals(run.getErrorCode())) {
                try {
                    JsonNode trace = run.getModelTraceJson() == null || run.getModelTraceJson().isBlank()
                            ? null : objectMapper.readTree(run.getModelTraceJson());
                    failure = AgentRunFailureClassifier.classifyTimeoutTrace(trace, run.getMaxModelCalls());
                    if (!"RUN_TIMEOUT".equals(failure.code())) {
                        data.put("inferredErrorCode", failure.code());
                        data.put("inferredMessage", failure.userMessage());
                    }
                } catch (Exception ignored) {
                    // Keep the durable timeout classification when old traces are unavailable.
                }
            }
            data.put("failureCategory", failure.category());
            data.put("retryable", failure.retryable());
        }
        if (run.getErrorMessage() != null) data.put("errorMessage", run.getErrorMessage());
        return data;
    }

    private Object modelDiagnostics(String runId) {
        if (runMapper == null) return null;
        AgentRunRecord run = runMapper.selectByRunId(runId);
        if (run == null || run.getModelTraceJson() == null || run.getModelTraceJson().isBlank()) return null;
        try {
            return Map.of("modelCalls", objectMapper.readTree(run.getModelTraceJson()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)
                || "WAITING_USER".equals(status) || "WAITING_CLIENT".equals(status);
    }

    /** Small diagnostics projection used by the UI and temporary acceptance runner; paper text is never exposed here. */
    private Map<String, Object> toolEventData(AgentToolCallRecord call) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", call.getToolCallId());
        data.put("toolName", call.getToolName());
        data.put("status", call.getStatus());
        if (call.getStartedAt() != null && call.getCompletedAt() != null) {
            data.put("durationMs", Math.max(0, Duration.between(call.getStartedAt(), call.getCompletedAt()).toMillis()));
        }
        if (call.getErrorCode() != null) data.put("errorCode", call.getErrorCode());
        if (call.getErrorMessage() != null && !call.getErrorMessage().isBlank()) {
            data.put("errorMessage", call.getErrorMessage().length() <= 500
                    ? call.getErrorMessage() : call.getErrorMessage().substring(0, 500));
        }
        appendReadResultDiagnostics(data, call.getResultJson());
        appendSkillActivationDiagnostics(data, call.getToolName(), call.getResultJson());
        return data;
    }

    private void appendSkillActivationDiagnostics(Map<String, Object> data, String toolName, String resultJson) {
        if (!"activate_skill".equals(toolName) || resultJson == null || resultJson.isBlank()) return;
        try {
            JsonNode result = objectMapper.readTree(resultJson);
            String skillName = result.path("skillName").asText("").trim();
            if (!skillName.isBlank()) data.put("skillName", skillName);
        } catch (Exception ignored) {
            // Activation diagnostics are best effort and must never break event delivery.
        }
    }

    private void appendReadResultDiagnostics(Map<String, Object> data, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return;
        try {
            JsonNode result = objectMapper.readTree(resultJson);
            String resultStatus = result.path("status").asText("");
            if (!resultStatus.isBlank()) data.put("resultStatus", resultStatus);
            JsonNode sources = result.path("sources");
            if (sources.isArray()) {
                List<String> sourceIds = new ArrayList<>();
                sources.forEach(source -> {
                    String id = source.path("sourceObjectId").asText("");
                    if (!id.isBlank()) sourceIds.add(id);
                });
                data.put("returnedSourceObjectIds", sourceIds);
                data.put("responseBytes", resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            JsonNode needs = result.path("evidenceNeeds");
            if (needs.isArray()) {
                int found = 0;
                List<Map<String, Object>> progress = new ArrayList<>();
                for (JsonNode need : needs) {
                    if ("found".equals(need.path("retrievalStatus").asText())) found++;
                }
                for (JsonNode need : needs) {
                    JsonNode state = need.path("progress");
                    if (!state.isObject()) continue;
                    Map<String, Object> value = new LinkedHashMap<>();
                    String needId = need.path("needId").asText("");
                    if (!needId.isBlank()) value.put("needId", needId);
                    value.put("outcome", state.path("outcome").asText(""));
                    value.put("attempt", state.path("attempt").asInt(0));
                    value.put("refinementCount", state.path("refinementCount").asInt(0));
                    value.put("newSourceObjectIds", sourceIds(state.path("newSourceObjectIds")));
                    value.put("recommendedAction", state.path("recommendedAction").asText(""));
                    value.put("reason", state.path("reason").asText(""));
                    progress.add(value);
                }
                data.put("evidenceNeedCount", needs.size());
                data.put("evidenceNeedFoundCount", found);
                if (!progress.isEmpty()) data.put("evidenceNeedProgress", progress);
            }
            JsonNode issues = result.path("issues");
            if (issues.isArray()) {
                data.put("validationIssueCount", issues.size());
                List<String> issueCodes = new ArrayList<>();
                issues.forEach(issue -> {
                    String code = issue.path("code").asText("");
                    if (!code.isBlank()) issueCodes.add(code);
                });
                data.put("validationIssueCodes", issueCodes);
            }
        } catch (Exception ignored) {
            // Tool results are heterogeneous. Missing diagnostics must never break event delivery.
        }
    }

    private List<String> sourceIds(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> ids = new ArrayList<>();
        values.forEach(value -> {
            String id = value.asText("").trim();
            if (!id.isBlank()) ids.add(id);
        });
        return ids;
    }
}
