package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeConflictException;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentStateMachine;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Cancels a durable Agent run while preventing a late provider result from being saved. */
@Service
public class AgentRunCancellationService {

    private static final String CANCEL_MESSAGE_KEY_PREFIX = "agent-cancelled-";

    private final AgentRuntimeService runtimeService;
    private final AgentTurnSubmissionService submissionService;
    private final AgentToolCallMapper toolCallMapper;
    private final ResearchMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final AgentLoopService loopService;

    public AgentRunCancellationService(AgentRuntimeService runtimeService,
                                       AgentTurnSubmissionService submissionService,
                                       AgentToolCallMapper toolCallMapper,
                                       ResearchMessageMapper messageMapper,
                                       ObjectMapper objectMapper,
                                       AgentLoopService loopService) {
        this.runtimeService = runtimeService;
        this.submissionService = submissionService;
        this.toolCallMapper = toolCallMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.loopService = loopService;
    }

    @Transactional
    public AgentTurnResult cancel(String runId) {
        AgentRunRecord run = runtimeService.getRun(runId);
        AgentRunStatus current = AgentRunStatus.valueOf(run.getStatus());
        if (current.isTerminal()) return loopService.currentResult(runId);
        AgentStateMachine.requireRunTransition(current, AgentRunStatus.CANCELLED);

        AgentTurnRecord turn = runtimeService.getTurnForRun(runId);
        AgentTurnResult cancelled = new AgentTurnResult(turn.getTurnId(), runId,
                AgentRunStatus.CANCELLED.name(), "已取消回答", List.of(), List.of());
        String resultJson = write(cancelled);
        try {
            runtimeService.transitionRun(runId, AgentRunStatus.CANCELLED, resultJson,
                    "USER_CANCELLED", "用户取消回答");
        } catch (AgentRuntimeConflictException | IllegalStateException race) {
            AgentRunRecord latest = runtimeService.getRun(runId);
            if (AgentRunStatus.CANCELLED.name().equals(latest.getStatus())
                    || AgentRunStatus.COMPLETED.name().equals(latest.getStatus())
                    || AgentRunStatus.FAILED.name().equals(latest.getStatus())) {
                return loopService.currentResult(runId);
            }
            throw race;
        }

        // Make the durable state visible before interrupting the local worker. If the
        // provider ignores interruption, its late transition will fail the CAS guard.
        submissionService.cancelExecution(runId);
        cancelOutstandingToolCalls(runId);
        persistCancellationMessage(turn, runId);
        return cancelled;
    }

    private void cancelOutstandingToolCalls(String runId) {
        List<AgentToolCallRecord> calls = toolCallMapper.selectByRunId(runId);
        if (calls == null) return;
        for (AgentToolCallRecord call : calls) {
            AgentToolCallStatus status;
            try {
                status = AgentToolCallStatus.valueOf(call.getStatus());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (status.isTerminal()) continue;
            try {
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.CANCELLED,
                        null, "USER_CANCELLED", "用户取消回答");
            } catch (AgentRuntimeConflictException | IllegalStateException ignored) {
                // A concurrently completed tool call is harmless; the run cancellation
                // remains authoritative and late output cannot become a final answer.
            }
        }
    }

    private void persistCancellationMessage(AgentTurnRecord turn, String runId) {
        String messageKey = CANCEL_MESSAGE_KEY_PREFIX + runId;
        if (messageMapper.selectByMessageKey(turn.getSessionId(), messageKey) != null) return;
        ResearchMessage message = new ResearchMessage();
        message.setSessionId(turn.getSessionId());
        message.setMessageKey(messageKey);
        message.setRole("ASSISTANT");
        message.setMessageType("RUN_STATUS");
        message.setMessageStatus("FINAL");
        message.setContent("已取消回答");
        message.setRunId(runId);
        message.setAgentTurnId(turn.getId());
        messageMapper.insert(message);
        try {
            runtimeService.bindFinalMessage(turn.getTurnId(), messageKey);
        } catch (AgentRuntimeConflictException ignored) {
            // A final message written by a winner of the cancellation race is retained.
        }
    }

    private String write(AgentTurnResult result) {
        try {
            return objectMapper.copy().findAndRegisterModules().writeValueAsString(result);
        } catch (Exception error) {
            throw new IllegalStateException("取消结果序列化失败", error);
        }
    }
}
