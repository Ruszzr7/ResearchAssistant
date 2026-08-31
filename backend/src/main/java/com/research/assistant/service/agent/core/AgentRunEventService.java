package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentRunEvent;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.AgentRunMapper;
import com.research.assistant.entity.AgentRunRecord;
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
            events.add(new AgentRunEvent(++id, "run.failed", state));
        }
        return events.stream().filter(event -> event.id() > afterId).toList();
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
        appendReadResultDiagnostics(data, call.getResultJson());
        return data;
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
                for (JsonNode need : needs) if ("found".equals(need.path("status").asText())) found++;
                data.put("evidenceNeedCount", needs.size());
                data.put("evidenceNeedFoundCount", found);
            }
            if (result.has("coverage")) data.put("coverage", result.path("coverage").asText());
            if (result.has("newSourceCount")) data.put("newSourceCount", result.path("newSourceCount").asInt());
            if (result.has("exhausted")) data.put("exhausted", result.path("exhausted").asBoolean());
        } catch (Exception ignored) {
            // Tool results are heterogeneous. Missing diagnostics must never break event delivery.
        }
    }
}
