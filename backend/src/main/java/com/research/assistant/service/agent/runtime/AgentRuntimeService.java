package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.mapper.AgentRunMapper;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.AgentTurnMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.core.AgentModelCallTrace;

@Service
public class AgentRuntimeService {

    private final AgentTurnMapper turnMapper;
    private final AgentRunMapper runMapper;
    private final AgentToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;

    public AgentRuntimeService(AgentTurnMapper turnMapper,
                               AgentRunMapper runMapper,
                               AgentToolCallMapper toolCallMapper,
                               ObjectMapper objectMapper) {
        this.turnMapper = turnMapper;
        this.runMapper = runMapper;
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentTurnRecord createTurn(long sessionId, String clientRequestId, String initialMessageKey) {
        if (sessionId <= 0) throw new IllegalArgumentException("sessionId must be positive");
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        if (turnMapper.lockSession(sessionId) == null) {
            throw new IllegalArgumentException("research session not found: " + sessionId);
        }

        String requestId = clientRequestId.trim();
        AgentTurnRecord existing = turnMapper.selectByClientRequest(sessionId, requestId);
        if (existing != null) return existing;

        AgentTurnRecord active = turnMapper.selectActiveBySession(sessionId);
        if (active != null) {
            throw new AgentRuntimeConflictException("session already has an active turn: " + active.getTurnId());
        }

        AgentTurnRecord record = new AgentTurnRecord();
        record.setTurnId(UUID.randomUUID().toString());
        record.setSessionId(sessionId);
        record.setClientRequestId(requestId);
        record.setSequenceNo(turnMapper.selectMaxSequence(sessionId) + 1);
        record.setStatus(AgentRunStatus.QUEUED.name());
        record.setInitialMessageKey(initialMessageKey);
        record.setVersion(0);
        turnMapper.insert(record);
        return record;
    }

    @Transactional
    public AgentRunRecord startRun(String turnId,
                                   AgentModelSnapshot model,
                                   AgentRunBudget budget,
                                   String contextSchemaVersion,
                                   String contextSnapshotJson,
                                   String documentHash,
                                   String parserVersion) {
        AgentTurnRecord turn = requireTurn(turnId);
        AgentRunRecord existing = runMapper.selectLatestByTurn(turn.getId());
        if (existing != null) return existing;
        if (AgentRunStatus.valueOf(turn.getStatus()) != AgentRunStatus.QUEUED) {
            throw new AgentRuntimeConflictException("turn is not queued: " + turnId);
        }

        AgentRunRecord run = new AgentRunRecord();
        run.setRunId(UUID.randomUUID().toString());
        run.setTurnId(turn.getId());
        run.setAttemptNo(1);
        run.setStatus(AgentRunStatus.RUNNING.name());
        run.setModelConfigVersion(model.configVersion());
        run.setModelCapabilitySignature(model.capabilitySignature().toLowerCase());
        run.setModelSnapshotJson(model.snapshotJson());
        run.setContextSchemaVersion(requireText(contextSchemaVersion, "contextSchemaVersion"));
        run.setContextSnapshotJson(requireText(contextSnapshotJson, "contextSnapshotJson"));
        run.setDocumentHash(documentHash);
        run.setParserVersion(parserVersion);
        run.setMaxModelCalls(budget.maxModelCalls());
        run.setMaxToolCalls(budget.maxToolCalls());
        run.setTokenBudget(budget.tokenBudget());
        run.setTimeoutMs(budget.timeoutMs());
        run.setModelCalls(0);
        run.setToolCalls(0);
        run.setPromptTokens(0);
        run.setCompletionTokens(0);
        run.setVersion(0);
        runMapper.insert(run);

        int updated = turnMapper.transition(turn.getId(), AgentRunStatus.QUEUED.name(),
                AgentRunStatus.RUNNING.name(), value(turn.getVersion()), false, null, null);
        requireSingleUpdate(updated, "turn was concurrently changed while starting");
        return run;
    }

    @Transactional
    public AgentRunRecord transitionRun(String runId,
                                        AgentRunStatus target,
                                        String resultJson,
                                        String errorCode,
                                        String errorMessage) {
        AgentRunRecord run = requireRun(runId);
        AgentRunStatus current = AgentRunStatus.valueOf(run.getStatus());
        AgentStateMachine.requireRunTransition(current, target);
        int updated = runMapper.transition(run.getId(), current.name(), target.name(), value(run.getVersion()),
                target.isTerminal(), resultJson, errorCode, errorMessage);
        requireSingleUpdate(updated, "run was concurrently changed: " + runId);

        AgentTurnRecord turn = turnMapper.selectById(run.getTurnId());
        if (turn == null) throw new IllegalStateException("agent turn disappeared for run: " + runId);
        AgentRunStatus turnCurrent = AgentRunStatus.valueOf(turn.getStatus());
        if (turnCurrent != target) {
            AgentStateMachine.requireRunTransition(turnCurrent, target);
            int turnUpdated = turnMapper.transition(turn.getId(), turnCurrent.name(), target.name(),
                    value(turn.getVersion()), target.isTerminal(), errorCode, errorMessage);
            requireSingleUpdate(turnUpdated, "turn was concurrently changed for run: " + runId);
        }
        run.setStatus(target.name());
        run.setVersion(value(run.getVersion()) + 1);
        run.setResultJson(resultJson);
        run.setErrorCode(errorCode);
        run.setErrorMessage(errorMessage);
        return run;
    }

    @Transactional
    public AgentToolCallRecord registerToolCall(String runId,
                                                String toolName,
                                                String argumentsJson,
                                                boolean readOnly,
                                                String idempotencyKey) {
        AgentRunRecord run = requireRun(runId);
        if (AgentRunStatus.valueOf(run.getStatus()) != AgentRunStatus.RUNNING) {
            throw new AgentRuntimeConflictException("tools can only be requested by a running run");
        }
        String key = requireText(idempotencyKey, "idempotencyKey");
        AgentToolCallRecord existing = toolCallMapper.selectByIdempotencyKey(key);
        if (existing != null) {
            if (!Objects.equals(existing.getRunId(), runId)
                    || !Objects.equals(existing.getToolName(), toolName)
                    || !Objects.equals(existing.getArgumentsJson(), argumentsJson)) {
                throw new AgentRuntimeConflictException("idempotency key was reused with different tool input");
            }
            return existing;
        }

        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setToolCallId(UUID.randomUUID().toString());
        call.setRunId(runId);
        call.setOrdinalNo(toolCallMapper.selectMaxOrdinal(runId) + 1);
        call.setToolName(requireText(toolName, "toolName"));
        call.setArgumentsJson(requireText(argumentsJson, "argumentsJson"));
        call.setStatus(AgentToolCallStatus.REQUESTED.name());
        call.setReadOnly(readOnly);
        call.setIdempotencyKey(key);
        call.setAttemptCount(0);
        call.setVersion(0);
        toolCallMapper.insert(call);
        return call;
    }

    @Transactional
    public AgentToolCallRecord transitionToolCall(String toolCallId,
                                                  AgentToolCallStatus target,
                                                  String resultJson,
                                                  String errorCode,
                                                  String errorMessage) {
        AgentToolCallRecord call = requireToolCall(toolCallId);
        AgentToolCallStatus current = AgentToolCallStatus.valueOf(call.getStatus());
        AgentStateMachine.requireToolTransition(current, target);
        int increment = target == AgentToolCallStatus.RUNNING ? 1 : 0;
        int updated = toolCallMapper.transition(call.getId(), current.name(), target.name(), value(call.getVersion()),
                target.isTerminal(), increment, resultJson, errorCode, errorMessage);
        requireSingleUpdate(updated, "tool call was concurrently changed: " + toolCallId);
        call.setStatus(target.name());
        call.setVersion(value(call.getVersion()) + 1);
        call.setAttemptCount(value(call.getAttemptCount()) + increment);
        call.setResultJson(resultJson);
        call.setErrorCode(errorCode);
        call.setErrorMessage(errorMessage);
        return call;
    }

    public AgentRunRecord getRun(String runId) {
        return requireRun(runId);
    }

    public AgentTurnRecord getTurn(String turnId) {
        return requireTurn(turnId);
    }

    public AgentTurnRecord getTurnForRun(String runId) {
        AgentRunRecord run = requireRun(runId);
        AgentTurnRecord turn = turnMapper.selectById(run.getTurnId());
        if (turn == null) throw new IllegalStateException("agent turn disappeared for run: " + runId);
        return turn;
    }

    @Transactional
    public void bindFinalMessage(String turnId, String messageKey) {
        AgentTurnRecord turn = requireTurn(turnId);
        int updated = turnMapper.bindFinalMessage(turn.getId(), requireText(messageKey, "messageKey"));
        if (updated != 1 && !Objects.equals(turnMapper.selectById(turn.getId()).getFinalMessageKey(), messageKey)) {
            throw new AgentRuntimeConflictException("turn already has a different final message");
        }
    }

    /** Records framework-reported usage after an invocation; counts are diagnostic metadata. */
    @Transactional
    public void recordUsage(String runId, int modelCalls, int toolCalls,
                            int promptTokens, int completionTokens) {
        if (modelCalls < 0 || toolCalls < 0 || promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("usage values must be non-negative");
        }
        int updated = runMapper.recordUsage(requireText(runId, "runId"), modelCalls, toolCalls,
                promptTokens, completionTokens);
        requireSingleUpdate(updated, "agent usage could not be recorded: " + runId);
    }

    /** Appends one content-free model-call trace so failed loops remain diagnosable. */
    @Transactional
    public void recordModelCall(String runId, AgentModelCallTrace trace) {
        AgentRunRecord run = requireRun(runId);
        try {
            java.util.List<AgentModelCallTrace> traces = new java.util.ArrayList<>();
            if (run.getModelTraceJson() != null && !run.getModelTraceJson().isBlank()) {
                traces.addAll(objectMapper.readValue(run.getModelTraceJson(),
                        objectMapper.getTypeFactory().constructCollectionType(java.util.List.class,
                                AgentModelCallTrace.class)));
            }
            traces.add(trace);
            int updated = runMapper.recordModelTrace(runId, objectMapper.writeValueAsString(traces));
            requireSingleUpdate(updated, "agent model trace could not be recorded: " + runId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize agent model trace", error);
        }
    }

    private AgentTurnRecord requireTurn(String turnId) {
        AgentTurnRecord turn = turnMapper.selectByTurnId(requireText(turnId, "turnId"));
        if (turn == null) throw new IllegalArgumentException("agent turn not found: " + turnId);
        return turn;
    }

    private AgentRunRecord requireRun(String runId) {
        AgentRunRecord run = runMapper.selectByRunId(requireText(runId, "runId"));
        if (run == null) throw new IllegalArgumentException("agent run not found: " + runId);
        return run;
    }

    private AgentToolCallRecord requireToolCall(String toolCallId) {
        AgentToolCallRecord call = toolCallMapper.selectByToolCallId(requireText(toolCallId, "toolCallId"));
        if (call == null) throw new IllegalArgumentException("tool call not found: " + toolCallId);
        return call;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static void requireSingleUpdate(int updated, String message) {
        if (updated != 1) throw new AgentRuntimeConflictException(message);
    }
}
