package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.dto.agent.AgentPendingAction;
import com.research.assistant.dto.agent.AgentEvidenceView;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentRunBudget;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import com.research.assistant.service.agent.runtime.AgentRunFailureClassifier;
import com.research.assistant.service.agent.runtime.AgentAttachmentService;
import com.research.assistant.service.agent.source.CitationRequest;
import com.research.assistant.service.agent.source.GroundEvidenceService;
import com.research.assistant.service.agent.source.GroundedAnswer;
import com.research.assistant.service.agent.action.ActionTicketService;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.agent.skill.PaperActionSkillTool;
import com.research.assistant.service.agent.skill.PaperEvidenceSkillTool;
import com.research.assistant.service.agent.skill.PaperProfileSkillTool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;

@Service
public class AgentLoopService {

    // Interactive paper answers retain a final liveness boundary if a provider call stalls.
    // Normal Agent decisions are not constrained by a tool-round workflow budget.
    private static final AgentRunBudget DEFAULT_BUDGET = AgentRunBudget.defaults();
    private static final String ANSWER_SCHEMA = """
            {"type":"object","properties":{
            "answerBlocks":{"type":"array","items":{"type":"object","properties":{
            "text":{"type":"string","description":"Complete GitHub-flavored Markdown. Use $...$ or $$...$$ for LaTeX math; do not add numeric citation markers."},"sourceObjectIds":{"type":"array","items":{"type":"string"}}},
            "required":["text","sourceObjectIds"],"additionalProperties":false}},
            "clarification":{"type":"string","description":"One concise natural-language question when the request is genuinely ambiguous; leave answerBlocks empty or omit it."}},
            "required":[],"additionalProperties":false}
            """;
    private static final String CLARIFICATION_SCHEMA = """
            {"type":"object","properties":{"question":{"type":"string"}},
            "required":["question"],"additionalProperties":false}
            """;

    private final AgentRuntimeService runtimeService;
    private final AgentContextAssembler contextAssembler;
    private final AgentModelSnapshotService snapshotService;
    private final PaperAgentFrameworkExecutor frameworkExecutor;
    private final PaperEvidenceSkillTool evidenceSkillTool;
    private final PaperProfileSkillTool profileSkillTool;
    private final PaperActionSkillTool actionSkillTool;
    private final GroundEvidenceService evidenceService;
    private final ResearchMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final ActionTicketService ticketService;
    private final AiCapabilityService capabilityService;
    private final AgentAttachmentService attachmentService;
    private final AgentSkillRegistry skillRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentLoopService(AgentRuntimeService runtimeService, AgentContextAssembler contextAssembler,
                            AgentModelSnapshotService snapshotService, PaperAgentFrameworkExecutor frameworkExecutor,
                            PaperEvidenceSkillTool evidenceSkillTool, PaperProfileSkillTool profileSkillTool,
                            PaperActionSkillTool actionSkillTool, GroundEvidenceService evidenceService,
                            ResearchMessageMapper messageMapper, ObjectMapper objectMapper,
                            ActionTicketService ticketService,
                            AiCapabilityService capabilityService, AgentAttachmentService attachmentService,
                            AgentSkillRegistry skillRegistry) {
        this.runtimeService = runtimeService;
        this.contextAssembler = contextAssembler;
        this.snapshotService = snapshotService;
        this.frameworkExecutor = frameworkExecutor;
        this.evidenceSkillTool = evidenceSkillTool;
        this.profileSkillTool = profileSkillTool;
        this.actionSkillTool = actionSkillTool;
        this.evidenceService = evidenceService;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.ticketService = ticketService;
        this.capabilityService = capabilityService;
        this.attachmentService = attachmentService;
        this.skillRegistry = skillRegistry;
    }

    /** Constructor retained for focused tests and small embedders. */
    public AgentLoopService(AgentRuntimeService runtimeService, AgentContextAssembler contextAssembler,
                            AgentModelSnapshotService snapshotService, PaperAgentFrameworkExecutor frameworkExecutor,
                            PaperReadToolRegistry toolRegistry, GroundEvidenceService evidenceService,
                            ResearchMessageMapper messageMapper, ObjectMapper objectMapper,
                            PaperActionResolver actionResolver, ActionTicketService ticketService,
                            AiCapabilityService capabilityService, AgentAttachmentService attachmentService) {
        this(runtimeService, contextAssembler, snapshotService, frameworkExecutor,
                new PaperEvidenceSkillTool(toolRegistry), null, new PaperActionSkillTool(actionResolver),
                evidenceService, messageMapper, objectMapper, ticketService, capabilityService, attachmentService,
                new AgentSkillRegistry(new PaperEvidenceSkillTool(toolRegistry), null,
                        new PaperActionSkillTool(actionResolver), java.nio.file.Path.of("../skills")));
    }

    public AgentTurnResult execute(AgentTurnInput input) {
        PreparedTurn prepared = prepare(input);
        if (prepared.duplicateRequest()) return currentResult(prepared.run().getRunId());
        return executePrepared(prepared);
    }

    PreparedTurn prepare(AgentTurnInput input) {
        AgentContextSnapshot context = contextAssembler.assemble(input);
        AgentTurnRecord turn;
        AgentRunRecord run;
        boolean duplicateRequest = false;
        if (input.resumeRunId() != null) {
            run = runtimeService.getRun(input.resumeRunId());
            if (!AgentRunStatus.WAITING_USER.name().equals(run.getStatus())) {
                throw new IllegalArgumentException("only a run waiting for user clarification can be resumed");
            }
            turn = runtimeService.getTurnForRun(run.getRunId());
            if (turn.getSessionId() != input.conversationId()) throw new IllegalArgumentException("resume run belongs to another conversation");
            saveUserMessage(input, turn, run.getRunId());
            run = runtimeService.transitionRun(run.getRunId(), AgentRunStatus.RUNNING, null, null, null);
        } else {
            String messageKey = "agent-user-" + input.clientRequestId();
            turn = runtimeService.createTurn(input.conversationId(), input.clientRequestId(), messageKey);
            ResearchMessage existing = messageMapper.selectByMessageKey(input.conversationId(), messageKey);
            duplicateRequest = existing != null;
            if (existing == null) existing = saveUserMessage(input, turn, null);
            run = runtimeService.startRun(turn.getTurnId(), snapshotService.current(), DEFAULT_BUDGET,
                    AgentContextAssembler.SCHEMA_VERSION, context.snapshotJson(),
                    context.sourceCatalog() == null ? null : context.sourceCatalog().documentHash(),
                    context.sourceCatalog() == null ? null : context.sourceCatalog().parserVersion());
            if (existing.getRunId() == null) messageMapper.bindRun(existing.getId(), run.getRunId());
            claimAttachments(input, turn);
        }

        if (input.resumeRunId() != null) claimAttachments(input, turn);
        return new PreparedTurn(input, context, turn, run, duplicateRequest);
    }

    AgentTurnResult executePrepared(PreparedTurn prepared) {
        AgentTurnInput input = prepared.input();
        AgentContextSnapshot context = prepared.context();
        AgentTurnRecord turn = prepared.turn();
        AgentRunRecord run = prepared.run();
        if (input.explicitAction() != null) {
            return executeExplicitAction(input, context, turn, run);
        }
        // Capability records are advisory diagnostics. A transient probe failure must not
        // disable ordinary chat; the actual model invocation remains the source of truth.

        Set<String> readSources = new LinkedHashSet<>(context.preReadSourceIds());
        Map<String, AgentToolExecution> readToolCache = new HashMap<>();
        EvidenceReadState evidenceReadState = new EvidenceReadState();
        List<AgentToolDefinition> definitions = new ArrayList<>();
        List<AgentSkillBinding> skills = skillRegistry.bindings(context, input.userMessage());
        definitions.add(new AgentToolDefinition("submit_answer",
                "Submit the final answer as ordered answerBlocks. For a paper-dependent answer, this is the required final step after reading paper evidence so the server can create clickable citations. Each block is complete GitHub-flavored Markdown; keep one factual claim or one tightly related claim group per block, use $...$ for inline LaTeX and $$...$$ for display LaTeX, and do not add numeric citation markers. Attach only sourceObjectIds that were actually read and support that block; general explanation or an explicit statement that the paper does not provide evidence may remain uncited. If no paper evidence was needed, this tool is also valid for a direct structured answer.", ANSWER_SCHEMA));
        definitions.add(new AgentToolDefinition("ask_clarification",
                "Ask one concise natural-language clarification question only when a requested page operation or answer materially depends on missing or ambiguous user intent. Do not use it for ordinary solvable questions.",
                CLARIFICATION_SCHEMA));
        List<AgentChatEntry> messages = context.messages();
        try {
            AgentFrameworkResult frameworkResult = frameworkExecutor.execute(messages, definitions, skills,
                    request -> executeFrameworkTool(context, turn, run.getRunId(), readSources,
                            readToolCache, evidenceReadState, input.userMessage(), request),
                    (toolCallId, skillName, argumentsJson, instructions) -> persistSkillActivation(
                            run.getRunId(), toolCallId, skillName, argumentsJson, instructions),
                    trace -> runtimeService.recordModelCall(run.getRunId(), trace));
            runtimeService.recordUsage(run.getRunId(), frameworkResult.modelCalls(), frameworkResult.toolCalls(),
                    frameworkResult.promptTokens(), frameworkResult.completionTokens());
            if (frameworkResult.content() != null && !frameworkResult.content().isBlank()) {
                try {
                    AgentTurnResult structured = readResult(frameworkResult.content());
                    if (isStoredResult(structured, run.getRunId())) return structured;
                } catch (com.fasterxml.jackson.core.JsonProcessingException notStructured) {
                    if (evidenceReadState.hasUsableEvidence()) {
                        throw new IllegalStateException("GROUNDING_SUBMISSION_REQUIRED: paper evidence was read; final answer must use submit_answer");
                    }
                    // A direct Markdown answer remains valid when no usable paper evidence
                    // was loaded in this run.
                }
                if (evidenceReadState.hasUsableEvidence()) {
                    throw new IllegalStateException("GROUNDING_SUBMISSION_REQUIRED: paper evidence was read; final answer must use submit_answer");
                }
                return completeDirectAnswer(turn, run.getRunId(), frameworkResult.content());
            }
            throw new IllegalStateException("model returned an empty response");
        } catch (Exception error) {
            AgentRunRecord current = runtimeService.getRun(run.getRunId());
            if (AgentRunStatus.RUNNING.name().equals(current.getStatus())) {
                AgentRunFailureClassifier.Failure failure = AgentRunFailureClassifier.classify(error);
                runtimeService.transitionRun(run.getRunId(), AgentRunStatus.FAILED, null,
                        failure.code(), safeError(error));
            }
            throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
        }
    }

    private AgentToolExecution executeFrameworkTool(AgentContextSnapshot context,
                                        AgentTurnRecord turn,
                                        String runId,
                                        Set<String> readSources,
                                        Map<String, AgentToolExecution> readToolCache,
                                        EvidenceReadState evidenceReadState,
                                        String evidenceFocus,
                                        AgentToolRequest request) {
        boolean mutation = actionSkillTool.supports(request.name());
        AgentToolCallRecord call = runtimeService.registerToolCall(runId, request.name(),
                request.argumentsJson(), !mutation, runId + ":" + request.id());
        call = runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING,
                null, null, null);
        try {
            if ("ask_clarification".equals(request.name())) {
                String question = requiredText(objectMapper.readTree(request.argumentsJson()), "question");
                AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), runId,
                        AgentRunStatus.WAITING_USER.name(), question, List.of(), List.of());
                String resultJson = writeResult(waiting);
                if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.WAITING_USER, resultJson)) {
                    failLateToolCall(call);
                    return toolResult(writeResult(currentResult(runId)));
                }
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                        resultJson, null, null);
                saveAssistantMessage(turn, runId, "CLARIFICATION", question, null);
                return toolResult(resultJson);
            }
            if ("submit_answer".equals(request.name())) {
                JsonNode submission = objectMapper.readTree(request.argumentsJson());
                String clarification = optionalParameter(submission.path("clarification").asText(null));
                JsonNode submittedBlocks = submission.path("answerBlocks");
                if (clarification != null && (!submittedBlocks.isArray() || submittedBlocks.isEmpty())) {
                    AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), runId,
                            AgentRunStatus.WAITING_USER.name(), clarification, List.of(), List.of());
                    String resultJson = writeResult(waiting);
                    if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.WAITING_USER, resultJson)) {
                        failLateToolCall(call);
                        return toolResult(writeResult(currentResult(runId)));
                    }
                    runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                            resultJson, null, null);
                    saveAssistantMessage(turn, runId, "CLARIFICATION", clarification, null);
                    return toolResult(resultJson);
                }
                GroundedAnswer grounded = parseAndGround(request.argumentsJson(), context, readSources);
                List<AgentEvidenceView> evidence = evidenceViews(grounded, context);
                AgentTurnResult completed = new AgentTurnResult(turn.getTurnId(), runId,
                        AgentRunStatus.COMPLETED.name(), grounded.answer(), grounded.bindings(), evidence);
                String resultJson = writeResult(completed);
                if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.COMPLETED, resultJson)) {
                    failLateToolCall(call);
                    return toolResult(writeResult(currentResult(runId)));
                }
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                        resultJson, null, null);
                saveAssistantMessage(turn, runId, "CHAT", grounded.answer(), resultJson);
                return toolResult(resultJson);
            }
            if (actionSkillTool.supports(request.name())) {
                PaperActionSkillTool.PreparedAction preparedAction = actionSkillTool.prepare(context.sourceCatalog(),
                        readSources, request.argumentsJson(), objectMapper);
                ActionTicketService.IssuedActionTicket issued = ticketService.issue(runId, call,
                        preparedAction.type(), preparedAction.target(), preparedAction.content(), preparedAction.color());
                AgentPendingAction action = new AgentPendingAction(call.getToolCallId(), preparedAction.type(),
                        preparedAction.target(), preparedAction.content(), preparedAction.color(),
                        issued.ticket(), issued.expiresAt());
                AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), runId,
                        AgentRunStatus.WAITING_CLIENT.name(), "正在执行页面操作。", List.of(), List.of(), List.of(action));
                String resultJson = writeResult(waiting);
                if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.WAITING_CLIENT, resultJson)) {
                    failLateToolCall(call);
                    return toolResult(writeResult(currentResult(runId)));
                }
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.WAITING_CLIENT,
                        resultJson, null, null);
                return toolResult(resultJson);
            }
            if (profileSkillTool != null && profileSkillTool.supports(request.name())) {
                if (context.paperId() == null) {
                    throw new IllegalArgumentException("paper overview is not available");
                }
                AgentToolExecution overview = profileSkillTool.execute(context.paperId(), context.sourceCatalog());
                readSources.addAll(overview.sourceObjectIds());
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                        overview.resultJson(), null, null);
                return overview;
            }
            if (context.sourceCatalog() == null) throw new IllegalArgumentException("paper source is not ready");
            AgentToolExecution result;
            // Cache is a local execution optimization for an identical read request. It is
            // not a semantic rule: a changed query remains fully available to the Agent.
            String effectiveArguments = evidenceArguments(request.name(), request.argumentsJson(), evidenceFocus);
            String cacheKey = request.name() + "\n" + canonicalArguments(effectiveArguments);
            boolean reused;
            synchronized (readToolCache) {
                result = readToolCache.get(cacheKey);
                reused = result != null;
                if (result == null) {
                    result = evidenceSkillTool.execute(context.sourceCatalog(), request.name(), effectiveArguments);
                    readToolCache.put(cacheKey, result);
                }
            }
            Set<String> newSourceIds = new LinkedHashSet<>(result.sourceObjectIds());
            newSourceIds.removeAll(readSources);
            if (evidenceSkillTool.supports(request.name())) {
                if (!result.sourceObjectIds().isEmpty()) {
                    evidenceReadState.markUsableEvidence();
                }
                result = addEvidenceProgress(result, newSourceIds, reused);
            }
            readSources.addAll(result.sourceObjectIds());
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                    result.resultJson(), null, null);
            return result;
        } catch (Exception error) {
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED, null,
                    "TOOL_INPUT_OR_EXECUTION_FAILED", safeError(error));
            if (evidenceSkillTool.supports(request.name())) {
                return toolResult(readUnavailableResult());
            }
            throw error instanceof RuntimeException runtime ? runtime : new IllegalArgumentException(error);
        }
    }

    private void persistSkillActivation(String runId, String toolCallId, String skillName,
                                        String argumentsJson, String instructions) {
        if (runId == null || skillName == null || skillName.isBlank()
                || instructions == null || instructions.isBlank()) return;
        try {
            String stableRequestId = toolCallId == null || toolCallId.isBlank()
                    ? "activation-" + Integer.toHexString((skillName + argumentsJson).hashCode())
                    : toolCallId;
            AgentToolCallRecord call = runtimeService.registerToolCall(runId, "activate_skill",
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                    true, runId + ":activate:" + stableRequestId);
            call = runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING,
                    null, null, null);
            String resultJson = objectMapper.writeValueAsString(Map.of(
                    "skillName", skillName.trim(),
                    "instructions", instructions));
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                    resultJson, null, null);
        } catch (Exception ignored) {
            // Skill activation has already completed in the model loop. Its
            // transcript persistence is only for continuity on later turns.
        }
    }

    private String evidenceArguments(String toolName, String argumentsJson, String evidenceFocus) {
        if (!"retrieve_paper_evidence".equals(toolName)
                || evidenceFocus == null || evidenceFocus.isBlank()) return argumentsJson;
        try {
            JsonNode parsed = objectMapper.readTree(argumentsJson);
            if (parsed.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) parsed).put("_evidenceFocus", evidenceFocus);
                return objectMapper.writeValueAsString(parsed);
            }
        } catch (Exception ignored) {
            // The tool registry will report the original malformed arguments consistently.
        }
        return argumentsJson;
    }

    private AgentTurnResult completeDirectAnswer(AgentTurnRecord turn, String runId, String content) {
        String answer = stripModelCitationMarkers(content == null ? "" : content.trim());
        if (answer.isBlank()) throw new IllegalStateException("agent returned an empty direct answer");
        AgentTurnResult completed = new AgentTurnResult(turn.getTurnId(), runId,
                AgentRunStatus.COMPLETED.name(), answer, List.of(), List.of());
        String resultJson = writeResult(completed);
        if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.COMPLETED, resultJson)) {
            return currentResult(runId);
        }
        saveAssistantMessage(turn, runId, "CHAT", answer, resultJson);
        return completed;
    }

    /**
     * Report objective progress for a repeated read request. This is a generic
     * liveness hint for the model, not a paper workflow gate: different requests
     * remain available and the runtime never decides whether the answer is complete.
     */
    private AgentToolExecution addEvidenceProgress(AgentToolExecution execution,
                                                   Set<String> newSourceIds, boolean reused) {
        try {
            JsonNode parsed = objectMapper.readTree(execution.resultJson());
            if (!parsed.isObject()) return execution;
            com.fasterxml.jackson.databind.node.ObjectNode payload =
                    (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
            payload.put("reused", reused);
            payload.put("newSourceCount", newSourceIds.size());
            payload.set("newSourceObjectIds", objectMapper.valueToTree(newSourceIds));
            boolean noProgress = newSourceIds.isEmpty();
            payload.put("noProgress", noProgress);
            if (noProgress) {
                payload.put("exhausted", true);
                payload.put("stopReason", execution.sourceObjectIds().isEmpty()
                        ? "no_matching_evidence" : "no_new_evidence");
            }
            return new AgentToolExecution(objectMapper.writeValueAsString(payload),
                    execution.sourceObjectIds(), execution.visuals());
        } catch (Exception ignored) {
            // Diagnostics must never make an otherwise valid paper read unavailable.
            return execution;
        }
    }

    /**
     * The durable timeout scanner and the provider thread can finish the same run
     * concurrently. Make the run transition the visibility boundary: a late
     * provider result must not create an assistant message after timeout won.
     */
    private boolean transitionRunBeforePersistingResult(String runId, AgentRunStatus target,
                                                        String resultJson) {
        try {
            runtimeService.transitionRun(runId, target, resultJson, null, null);
            return true;
        } catch (RuntimeException transitionFailure) {
            try {
                AgentRunRecord current = runtimeService.getRun(runId);
                if (current != null && isTerminalStatus(current.getStatus())) return false;
            } catch (RuntimeException stateReadFailure) {
                transitionFailure.addSuppressed(stateReadFailure);
            }
            throw transitionFailure;
        }
    }

    private void failLateToolCall(AgentToolCallRecord call) {
        try {
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED,
                    null, "RUN_NOT_ACTIVE", "agent run was finalized before this tool result");
        } catch (RuntimeException ignored) {
            // The timeout thread may have finalized the run and the tool call at the same time.
        }
    }

    private static boolean isTerminalStatus(String status) {
        try {
            return status != null && AgentRunStatus.valueOf(status).isTerminal();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String readUnavailableResult() {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "unavailable",
                    "sources", List.of(),
                    "message", "The requested paper capability is unavailable in this turn. Continue from the conversation, selection, and any successfully loaded paper context. Do not invent citations, page numbers, formula numbers, or exact values."
            ));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return "{\"status\":\"unavailable\",\"sources\":[]}";
        }
    }

    private static AgentToolExecution toolResult(String resultJson) {
        return new AgentToolExecution(resultJson, Set.of());
    }

    private static boolean isStoredResult(AgentTurnResult result, String runId) {
        return result != null && runId != null && runId.equals(result.runId())
                && result.status() != null && !result.status().isBlank()
                && result.message() != null && !result.message().isBlank();
    }

    private static final class EvidenceReadState {
        private boolean usableEvidence;

        private void markUsableEvidence() {
            usableEvidence = true;
        }

        private boolean hasUsableEvidence() {
            return usableEvidence;
        }
    }

    void rejectPrepared(PreparedTurn prepared, RuntimeException error) {
        AgentRunRecord current = runtimeService.getRun(prepared.run().getRunId());
        if (AgentRunStatus.RUNNING.name().equals(current.getStatus())) {
            runtimeService.transitionRun(current.getRunId(), AgentRunStatus.FAILED, null,
                    "AGENT_DISPATCH_FAILED", safeError(error));
        }
    }

    record PreparedTurn(AgentTurnInput input, AgentContextSnapshot context,
                        AgentTurnRecord turn, AgentRunRecord run, boolean duplicateRequest) {
    }

    public AgentTurnResult currentResult(String runId) {
        AgentRunRecord run = runtimeService.getRun(runId);
        AgentTurnRecord turn = runtimeService.getTurnForRun(runId);
        if (run.getResultJson() != null) {
            try {
                return readResult(run.getResultJson());
            } catch (Exception error) {
                throw new IllegalStateException("stored agent result is invalid", error);
            }
        }
        ResearchMessage message = messageMapper.selectLatestAssistantByRun(runId);
        String content = message == null ? null : message.getContent();
        if ((content == null || content.isBlank()) && ("FAILED".equals(run.getStatus())
                || "CANCELLED".equals(run.getStatus()))) {
            content = AgentRunFailureClassifier.userMessage(run.getErrorCode());
        }
        return new AgentTurnResult(turn.getTurnId(), runId, run.getStatus(),
                content, List.of(), List.of());
    }

    private GroundedAnswer parseAndGround(String json, AgentContextSnapshot context, Set<String> readSources) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode blocks = root.path("answerBlocks");
        if (!blocks.isArray() || blocks.isEmpty()) throw new IllegalArgumentException("answerBlocks must not be empty");
        StringBuilder answer = new StringBuilder();
        List<CitationRequest> requests = new ArrayList<>();
        for (JsonNode block : blocks) {
            String text = stripModelCitationMarkers(requiredText(block, "text"));
            if (answer.length() > 0) answer.append("\n\n");
            int start = answer.length();
            answer.append(text);
            int end = answer.length();
            JsonNode sourceIds = block.path("sourceObjectIds");
            if (!sourceIds.isArray()) throw new IllegalArgumentException("sourceObjectIds must be an array");
            for (JsonNode sourceNode : sourceIds) {
                String sourceId = sourceNode.asText("").trim();
                if (sourceId.isEmpty()) throw new IllegalArgumentException("sourceObjectId is required");
                if (!readSources.contains(sourceId)) {
                    throw new IllegalArgumentException("citation source was not read: " + sourceId);
                }
                requests.add(new CitationRequest(start, end, sourceId, null, List.of()));
            }
        }
        if (requests.isEmpty()) return new GroundedAnswer(answer.toString(), List.of(), List.of());
        if (context.sourceCatalog() == null) throw new IllegalArgumentException("paper sources are unavailable");
        return evidenceService.ground(answer.toString(), requests, context.sourceCatalog());
    }

    static String stripModelCitationMarkers(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder output = new StringBuilder(value.length());
        boolean fencedCode = false;
        boolean inlineCode = false;
        boolean displayMath = false;
        boolean inlineMath = false;
        for (int index = 0; index < value.length();) {
            if (value.startsWith("```", index)) {
                fencedCode = !fencedCode;
                output.append("```");
                index += 3;
                continue;
            }
            char current = value.charAt(index);
            if (!fencedCode && current == '`') {
                inlineCode = !inlineCode;
                output.append(current);
                index++;
                continue;
            }
            if (!fencedCode && !inlineCode && current == '$') {
                boolean doubleDollar = index + 1 < value.length() && value.charAt(index + 1) == '$';
                if (doubleDollar) displayMath = !displayMath;
                else if (!displayMath) inlineMath = !inlineMath;
                output.append(doubleDollar ? "$$" : "$");
                index += doubleDollar ? 2 : 1;
                continue;
            }
            if (!fencedCode && !inlineCode && !displayMath && !inlineMath && current == '\\'
                    && index + 1 < value.length() && (value.charAt(index + 1) == '(' || value.charAt(index + 1) == '[')) {
                String close = value.charAt(index + 1) == '(' ? "\\)" : "\\]";
                int end = value.indexOf(close, index + 2);
                if (end >= 0) {
                    output.append(value, index, end + close.length());
                    index = end + close.length();
                    continue;
                }
            }
            if (!fencedCode && !inlineCode && !displayMath && !inlineMath && current == '[') {
                int close = value.indexOf(']', index + 1);
                if (close > index + 1) {
                    String marker = value.substring(index + 1, close);
                    if (isStandaloneCitationMarker(marker, value, index, close)) {
                        while (output.length() > 0 && Character.isWhitespace(output.charAt(output.length() - 1))) {
                            output.deleteCharAt(output.length() - 1);
                        }
                        index = close + 1;
                        continue;
                    }
                }
            }
            output.append(current);
            index++;
        }
        return output.toString();
    }

    private static boolean isStandaloneCitationMarker(String marker, String value, int start, int end) {
        String normalized = marker.replace('，', ',').replace('、', ',').replace('－', '-').trim();
        if (!normalized.matches("[1-9]\\d*(?:\\s*[,\\-]\\s*[1-9]\\d*)*")) return false;
        int previous = start - 1;
        while (previous >= 0 && Character.isWhitespace(value.charAt(previous))) previous--;
        if (previous >= 0 && "=∈∉≤≥<>+-*/^_([{".indexOf(value.charAt(previous)) >= 0) return false;
        int next = end + 1;
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
        return next >= value.length() || "，。！？.!?;；:：)）]】".indexOf(value.charAt(next)) >= 0;
    }

    private void claimAttachments(AgentTurnInput input, AgentTurnRecord turn) {
        List<String> ids = new ArrayList<>(input.attachmentIds());
        ids.addAll(input.formulaAttachmentIds());
        if (ids.isEmpty()) return;
        if (attachmentService == null) throw new IllegalStateException("attachment service is unavailable");
        attachmentService.claim(turn.getId(), input.conversationId(), ids);
    }

    private AgentTurnResult executeExplicitAction(AgentTurnInput input, AgentContextSnapshot context,
                                                  AgentTurnRecord turn, AgentRunRecord run) {
        Object sourceValue = input.explicitAction().parameters().get("sourceObjectId");
        String sourceId = sourceValue == null ? null : sourceValue.toString().trim();
        String content = optionalParameter(input.explicitAction().parameters().get("content"));
        String color = optionalParameter(input.explicitAction().parameters().get("color"));
        PaperActionSkillTool.PreparedAction preparedAction = actionSkillTool.prepareExplicit(context.sourceCatalog(),
                input.explicitAction().type(), sourceId, content, color);
        String arguments;
        try { arguments = objectMapper.writeValueAsString(input.explicitAction().parameters()); }
        catch (Exception error) { throw new IllegalArgumentException("explicit action parameters are invalid", error); }
        AgentToolCallRecord call = runtimeService.registerToolCall(run.getRunId(),
                "explicit_" + preparedAction.type().name().toLowerCase(), arguments, false,
                run.getRunId() + ":explicit:" + preparedAction.type() + ":" + sourceId);
        call = runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING, null, null, null);
        ActionTicketService.IssuedActionTicket issued = ticketService.issue(run.getRunId(), call,
                preparedAction.type(), preparedAction.target(), preparedAction.content(), preparedAction.color());
        AgentPendingAction action = new AgentPendingAction(call.getToolCallId(), preparedAction.type(),
                preparedAction.target(), preparedAction.content(), preparedAction.color(),
                issued.ticket(), issued.expiresAt());
        String message = "正在执行页面操作。";
        AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), run.getRunId(), AgentRunStatus.WAITING_CLIENT.name(),
                message, List.of(), List.of(), List.of(action));
        String resultJson = writeResult(waiting);
        if (!transitionRunBeforePersistingResult(run.getRunId(), AgentRunStatus.WAITING_CLIENT, resultJson)) {
            failLateToolCall(call);
            return currentResult(run.getRunId());
        }
        return waiting;
    }

    private static String optionalParameter(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }

    private String writeResult(AgentTurnResult result) {
        try {
            return objectMapper.copy().findAndRegisterModules().writeValueAsString(result);
        } catch (Exception error) {
            throw new IllegalStateException("failed to serialize agent result", error);
        }
    }

    private AgentTurnResult readResult(String json) throws Exception {
        return objectMapper.copy().findAndRegisterModules().readValue(json, AgentTurnResult.class);
    }

    private ResearchMessage saveUserMessage(AgentTurnInput input, AgentTurnRecord turn, String runId) {
        ResearchMessage message = baseMessage(input.conversationId(), "USER", "CHAT", input.userMessage(), turn, runId);
        message.setMessageKey("agent-user-" + input.clientRequestId());
        if (input.selectedContent() != null) {
            try { message.setSelectionAnchorJson(objectMapper.writeValueAsString(input.selectedContent())); }
            catch (Exception error) { throw new IllegalStateException("failed to serialize selection", error); }
        }
        List<String> attachmentIds = new ArrayList<>(input.attachmentIds());
        attachmentIds.addAll(input.formulaAttachmentIds());
        if (!attachmentIds.isEmpty()) {
            if (attachmentService == null) throw new IllegalStateException("attachment service is unavailable");
            try {
                var metadata = attachmentService.requireForSession(input.conversationId(), attachmentIds).stream()
                        .map(item -> java.util.Map.of(
                                "attachmentId", item.getAttachmentId(),
                                "name", item.getOriginalName() == null ? "附件" : item.getOriginalName(),
                                "mimeType", item.getMediaType(),
                                "extractionStatus", item.getExtractionStatus()))
                        .toList();
                message.setEvidenceJson(objectMapper.writeValueAsString(java.util.Map.of("attachments", metadata)));
                message.setEvidenceSchemaVersion("agent-input-v1");
            } catch (Exception error) {
                throw new IllegalStateException("failed to serialize attachment metadata", error);
            }
        }
        messageMapper.insert(message);
        return message;
    }

    private void saveAssistantMessage(AgentTurnRecord turn, String runId, String type, String content, String evidenceJson) {
        ResearchMessage message = baseMessage(turn.getSessionId(), "ASSISTANT", type, content, turn, runId);
        message.setMessageKey("agent-assistant-" + UUID.randomUUID());
        message.setEvidenceJson(evidenceJson);
        message.setEvidenceSchemaVersion(evidenceJson == null ? null : "ground-evidence-v1");
        messageMapper.insert(message);
        if (!"CLARIFICATION".equals(type)) runtimeService.bindFinalMessage(turn.getTurnId(), message.getMessageKey());
    }

    private ResearchMessage baseMessage(long sessionId, String role, String type, String content,
                                        AgentTurnRecord turn, String runId) {
        ResearchMessage message = new ResearchMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setMessageType(type);
        message.setMessageStatus("FINAL");
        message.setContent(content == null ? "" : content);
        message.setRunId(runId);
        message.setAgentTurnId(turn.getId());
        return message;
    }

    private static String requiredText(JsonNode root, String name) {
        String value = root.path(name).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static List<AgentEvidenceView> evidenceViews(GroundedAnswer grounded, AgentContextSnapshot context) {
        if (context.sourceCatalog() == null) return List.of();
        return grounded.evidenceEntries().stream().map(binding -> new AgentEvidenceView(binding.citationNumber(),
                binding.sourceObjectId(), context.paperId(), binding.quote(),
                context.sourceCatalog().requireObject(binding.sourceObjectId()).formulaNumber(),
                context.sourceCatalog().requireLocators(binding.sourceObjectId()))).toList();
    }

    private String canonicalArguments(String argumentsJson) {
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(argumentsJson));
        } catch (Exception ignored) {
            return argumentsJson == null ? "" : argumentsJson.trim();
        }
    }

    private static String safeError(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

}
