package com.research.assistant.service.agent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentPendingAction;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import org.springframework.stereotype.Service;

@Service
public class AgentActionTicketRenewalService {
    private final AgentRuntimeService runtimeService;
    private final AgentToolCallMapper toolCallMapper;
    private final PaperSourceCatalogService sourceService;
    private final PaperActionResolver resolver;
    private final ActionTicketService ticketService;
    private final ObjectMapper objectMapper;
    private final ResearchSessionMapper sessionMapper;

    public AgentActionTicketRenewalService(AgentRuntimeService runtimeService, AgentToolCallMapper toolCallMapper,
                                           PaperSourceCatalogService sourceService, PaperActionResolver resolver,
                                           ActionTicketService ticketService, ObjectMapper objectMapper,
                                           ResearchSessionMapper sessionMapper) {
        this.runtimeService = runtimeService;
        this.toolCallMapper = toolCallMapper;
        this.sourceService = sourceService;
        this.resolver = resolver;
        this.ticketService = ticketService;
        this.objectMapper = objectMapper;
        this.sessionMapper = sessionMapper;
    }

    public AgentPendingAction renew(String runId, String toolCallId) {
        AgentRunRecord run = runtimeService.getRun(runId);
        if (!AgentRunStatus.WAITING_CLIENT.name().equals(run.getStatus())) {
            throw new IllegalArgumentException("run is not waiting for a client action");
        }
        AgentToolCallRecord call = toolCallMapper.selectByToolCallId(toolCallId);
        if (call == null || !runId.equals(call.getRunId())
                || !AgentToolCallStatus.WAITING_CLIENT.name().equals(call.getStatus())) {
            throw new IllegalArgumentException("tool call is not renewable");
        }
        try {
            JsonNode args = objectMapper.readTree(call.getArgumentsJson());
            // A heterogeneous batch keeps the first operation on the parent
            // tool call; child calls store a singular operation object. Renew
            // the parent ticket from that first operation.
            JsonNode operation = args.path("operations").isArray()
                    && !args.path("operations").isEmpty() ? args.path("operations").get(0) : args;
            String sourceId = optional(operation, "sourceObjectId");
            if (sourceId == null && operation.path("sourceObjectIds").isArray()
                    && !operation.path("sourceObjectIds").isEmpty()) {
                sourceId = optional(operation.path("sourceObjectIds").get(0));
            }
            if (sourceId == null) throw new IllegalArgumentException("sourceObjectId is required");
            PaperActionType type = actionType(call.getToolName(), operation);
            PaperSourceCatalog catalog = sourceService.latest(runDocumentPaperId(run));
            if (run.getDocumentHash() == null || !run.getDocumentHash().equals(catalog.documentHash())) {
                throw new IllegalArgumentException("PDF version changed; action ticket cannot be renewed");
            }
            ActionTarget target = resolver.resolve(catalog, sourceId);
            String content = optional(operation, "content");
            String color = optional(operation, "color");
            var issued = ticketService.issue(runId, call, type, target, content, color);
            return new AgentPendingAction(toolCallId, type, target, content, color, issued.ticket(), issued.expiresAt());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("stored action request is invalid", error);
        }
    }

    private long runDocumentPaperId(AgentRunRecord run) {
        var turn = runtimeService.getTurnForRun(run.getRunId());
        var session = sessionMapper.selectById(turn.getSessionId());
        if (session == null || session.getPrimaryPaperId() == null) {
            throw new IllegalArgumentException("run has no primary paper");
        }
        return session.getPrimaryPaperId();
    }

    private static PaperActionType actionType(String name, JsonNode arguments) {
        if ("paper_action".equals(name)) {
            String value = optional(arguments, "actionType");
            if (value == null) throw new IllegalArgumentException("actionType is required");
            try {
                return PaperActionType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("actionType is invalid", error);
            }
        }
        return switch (name) {
            case "jump_to_source" -> PaperActionType.JUMP;
            case "highlight_source" -> PaperActionType.HIGHLIGHT;
            case "underline_source" -> PaperActionType.UNDERLINE;
            case "add_note" -> PaperActionType.NOTE;
            case "add_comment" -> PaperActionType.COMMENT;
            default -> throw new IllegalArgumentException("tool call is not a paper action");
        };
    }

    private static String required(JsonNode node, String name) {
        String value = optional(node, name);
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String optional(JsonNode node, String name) {
        String value = node.path(name).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String optional(JsonNode node) {
        String value = node == null ? "" : node.asText("").trim();
        return value.isEmpty() ? null : value;
    }
}
