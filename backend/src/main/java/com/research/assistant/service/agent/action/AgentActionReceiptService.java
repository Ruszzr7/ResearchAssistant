package com.research.assistant.service.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentActionReceiptRequest;
import com.research.assistant.dto.agent.AgentActionReceiptResult;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.entity.PaperAnnotation;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.PaperAnnotationMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import com.research.assistant.service.agent.source.GroundedAnswer;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentActionReceiptService {
    private final ActionTicketService ticketService;
    private final AgentToolCallMapper toolCallMapper;
    private final PaperAnnotationMapper annotationMapper;
    private final PaperSourceCatalogService sourceService;
    private final AgentRuntimeService runtimeService;
    private final ResearchMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public AgentActionReceiptService(ActionTicketService ticketService, AgentToolCallMapper toolCallMapper,
                                     PaperAnnotationMapper annotationMapper, PaperSourceCatalogService sourceService,
                                     AgentRuntimeService runtimeService, ResearchMessageMapper messageMapper,
                                     ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.toolCallMapper = toolCallMapper;
        this.annotationMapper = annotationMapper;
        this.sourceService = sourceService;
        this.runtimeService = runtimeService;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    public AgentActionReceiptResult accept(AgentActionReceiptRequest request) {
        ActionTicketPayload payload = ticketService.verify(request.ticket());
        AgentToolCallRecord call = toolCallMapper.selectByToolCallId(payload.toolCallId());
        if (AgentToolCallStatus.COMPLETED.name().equals(call.getStatus())) return completedResult(payload, call);
        if (!AgentToolCallStatus.WAITING_CLIENT.name().equals(call.getStatus())) {
            throw new IllegalArgumentException("tool call is not waiting for a client receipt");
        }
        if (!request.success()) {
            String error = safe(request.clientError(), "client action failed");
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED, null,
                    "CLIENT_ACTION_FAILED", error);
            failRun(payload.runId(), error);
            return new AgentActionReceiptResult(payload.runId(), payload.toolCallId(), "FAILED", null, error);
        }

        PaperSourceCatalog catalog = sourceService.latest(payload.paperId());
        if (!catalog.documentHash().equals(payload.documentHash())) {
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED, null,
                    "STALE_DOCUMENT_VERSION", "PDF version changed before action receipt");
            failRun(payload.runId(), "PDF version changed before action receipt");
            throw new IllegalArgumentException("PDF version changed; action was not accepted");
        }
        var source = catalog.requireObject(payload.sourceObjectId());
        var locators = catalog.requireLocators(source.sourceObjectId());
        validateClientCoordinates(request.actualCoordinates(), locators.get(0).pageNumber());

        Long annotationId = payload.actionType().createsAnnotation() ? persistAnnotation(payload, catalog) : null;
        String message = successMessage(payload.actionType());
        String resultJson = actionResultJson(payload, annotationId, message);
        runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED, resultJson, null, null);
        boolean allFinished = toolCallMapper.selectByRunId(payload.runId()).stream()
                .filter(tool -> !Boolean.TRUE.equals(tool.getReadOnly()))
                .allMatch(tool -> AgentToolCallStatus.COMPLETED.name().equals(tool.getStatus())
                        || tool.getToolCallId().equals(payload.toolCallId()));
        if (allFinished) completeRun(payload.runId(), message);
        return new AgentActionReceiptResult(payload.runId(), payload.toolCallId(),
                allFinished ? "COMPLETED" : "WAITING_CLIENT", annotationId, message);
    }

    private Long persistAnnotation(ActionTicketPayload payload, PaperSourceCatalog catalog) {
        PaperAnnotation existing = annotationMapper.selectByAgentToolCallId(payload.toolCallId());
        if (existing != null) return existing.getId();
        var locators = catalog.requireLocators(payload.sourceObjectId());
        PaperAnnotation annotation = new PaperAnnotation();
        annotation.setPaperId(payload.paperId());
        annotation.setType(payload.actionType().name());
        annotation.setPage(locators.get(0).pageNumber());
        annotation.setColor(payload.color() == null || payload.color().isBlank() ? "#ffeb3b" : payload.color());
        annotation.setNote(payload.content());
        annotation.setAiGenerated(true);
        annotation.setAgentToolCallId(payload.toolCallId());
        annotation.setDocumentHash(payload.documentHash());
        annotation.setSourceObjectId(payload.sourceObjectId());
        annotation.setCompleted(false);
        try {
            annotation.setCoordinatesJson(objectMapper.writeValueAsString(coordinates(payload.actionType(), locators)));
            annotationMapper.insert(annotation);
            return annotation.getId();
        } catch (DuplicateKeyException duplicate) {
            PaperAnnotation raced = annotationMapper.selectByAgentToolCallId(payload.toolCallId());
            if (raced != null) return raced.getId();
            throw duplicate;
        } catch (Exception error) {
            throw new IllegalStateException("failed to persist agent annotation", error);
        }
    }

    private Map<String, Object> coordinates(PaperActionType type,
                                            List<com.research.assistant.service.agent.source.SourceLocator> locators) {
        List<Map<String, Double>> quads = new ArrayList<>();
        locators.forEach(locator -> locator.rects().forEach(rect -> quads.add(Map.of(
                "x1", rect.x(), "y1", rect.y(), "x2", rect.right(), "y2", rect.y(),
                "x3", rect.right(), "y3", rect.bottom(), "x4", rect.x(), "y4", rect.bottom()))));
        Map<String, Object> coordinates = new LinkedHashMap<>();
        coordinates.put(type == PaperActionType.NOTE || type == PaperActionType.COMMENT ? "anchorQuads" : "quads", quads);
        coordinates.put("coordinateSpace", "PDF_NORMALIZED");
        coordinates.put("source", "AGENT_TRUSTED_LOCATOR");
        return coordinates;
    }

    private void completeRun(String runId, String text) {
        AgentRunRecord run = runtimeService.getRun(runId);
        if (AgentRunStatus.WAITING_CLIENT.name().equals(run.getStatus())) {
            runtimeService.transitionRun(runId, AgentRunStatus.RUNNING, null, null, null);
        }
        try {
            AgentTurnRecord turn = runtimeService.getTurnForRun(runId);
            AgentTurnResult result = new AgentTurnResult(turn.getTurnId(), runId, AgentRunStatus.COMPLETED.name(),
                    text, List.of(), List.of());
            String resultJson = objectMapper.writeValueAsString(result);
            ResearchMessage message = new ResearchMessage();
            message.setSessionId(turn.getSessionId()); message.setMessageKey("agent-assistant-" + UUID.randomUUID());
            message.setRole("ASSISTANT"); message.setMessageType("ACTION_RECEIPT"); message.setMessageStatus("FINAL");
            message.setContent(text); message.setRunId(runId); message.setAgentTurnId(turn.getId());
            messageMapper.insert(message);
            runtimeService.bindFinalMessage(turn.getTurnId(), message.getMessageKey());
            runtimeService.transitionRun(runId, AgentRunStatus.COMPLETED, resultJson, null, null);
        } catch (Exception error) {
            throw new IllegalStateException("failed to finalize action run", error);
        }
    }

    private void failRun(String runId, String error) {
        AgentRunRecord run = runtimeService.getRun(runId);
        if (AgentRunStatus.WAITING_CLIENT.name().equals(run.getStatus())) {
            runtimeService.transitionRun(runId, AgentRunStatus.RUNNING, null, null, null);
            runtimeService.transitionRun(runId, AgentRunStatus.FAILED, null, "CLIENT_ACTION_FAILED", error);
        }
    }

    private AgentActionReceiptResult completedResult(ActionTicketPayload payload, AgentToolCallRecord call) {
        PaperAnnotation annotation = annotationMapper.selectByAgentToolCallId(payload.toolCallId());
        return new AgentActionReceiptResult(payload.runId(), payload.toolCallId(), "COMPLETED",
                annotation == null ? null : annotation.getId(), successMessage(payload.actionType()));
    }

    private String actionResultJson(ActionTicketPayload payload, Long annotationId, String message) {
        try { return objectMapper.writeValueAsString(Map.of("actionType", payload.actionType(), "annotationId",
                annotationId == null ? "" : annotationId, "message", message)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static void validateClientCoordinates(Map<String, Object> coordinates, int expectedPage) {
        if (coordinates == null || coordinates.isEmpty()) return;
        Object page = coordinates.get("page");
        if (page instanceof Number value && value.intValue() != expectedPage) {
            throw new IllegalArgumentException("client receipt page does not match trusted target");
        }
    }

    private static String successMessage(PaperActionType type) {
        return switch (type) {
            case JUMP -> "已跳转到目标位置。";
            case HIGHLIGHT -> "已完成高亮。";
            case UNDERLINE -> "已添加下划线。";
            case NOTE -> "已添加笔记。";
            case COMMENT -> "已添加批注。";
        };
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() > 900 ? value.substring(0, 900) : value;
    }
}
