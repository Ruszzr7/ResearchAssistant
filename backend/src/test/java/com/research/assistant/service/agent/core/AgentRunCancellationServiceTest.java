package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunCancellationServiceTest {

    @Test
    void cancelsRunAndOutstandingToolCallsBeforePersistingAStatusMessage() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentTurnSubmissionService submission = mock(AgentTurnSubmissionService.class);
        AgentToolCallMapper tools = mock(AgentToolCallMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentRunRecord run = run("run-1", "RUNNING");
        AgentTurnRecord turn = turn();
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setToolCallId("tool-1");
        call.setRunId("run-1");
        call.setStatus(AgentToolCallStatus.RUNNING.name());
        when(runtime.getRun("run-1")).thenReturn(run);
        when(runtime.getTurnForRun("run-1")).thenReturn(turn);
        when(runtime.transitionRun(eq("run-1"), eq(AgentRunStatus.CANCELLED), anyString(),
                eq("USER_CANCELLED"), eq("用户取消回答"))).thenReturn(run);
        when(tools.selectByRunId("run-1")).thenReturn(List.of(call));
        when(messages.selectByMessageKey(eq(7L), eq("agent-cancelled-run-1"))).thenReturn(null);
        doAnswer(invocation -> {
            ((com.research.assistant.entity.ResearchMessage) invocation.getArgument(0)).setId(8L);
            return 1;
        }).when(messages).insert(any(com.research.assistant.entity.ResearchMessage.class));

        AgentRunCancellationService service = new AgentRunCancellationService(
                runtime, submission, tools, messages, new ObjectMapper(), loop);

        AgentTurnResult result = service.cancel("run-1");

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.message()).isEqualTo("已取消回答");
        verify(submission).cancelExecution("run-1");
        verify(runtime).transitionToolCall("tool-1", AgentToolCallStatus.CANCELLED,
                null, "USER_CANCELLED", "用户取消回答");
        verify(runtime).bindFinalMessage("turn-1", "agent-cancelled-run-1");
        verify(messages).insert(any(com.research.assistant.entity.ResearchMessage.class));
    }

    @Test
    void repeatedCancellationReturnsThePersistedTerminalResult() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentTurnSubmissionService submission = mock(AgentTurnSubmissionService.class);
        AgentToolCallMapper tools = mock(AgentToolCallMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentLoopService loop = mock(AgentLoopService.class);
        when(runtime.getRun("run-2")).thenReturn(run("run-2", "CANCELLED"));
        AgentTurnResult stored = new AgentTurnResult("turn-2", "run-2", "CANCELLED", "已取消回答",
                List.of(), List.of());
        when(loop.currentResult("run-2")).thenReturn(stored);

        AgentRunCancellationService service = new AgentRunCancellationService(
                runtime, submission, tools, messages, new ObjectMapper(), loop);

        assertThat(service.cancel("run-2")).isSameAs(stored);
        verify(submission, org.mockito.Mockito.never()).cancelExecution(anyString());
    }

    private AgentRunRecord run(String id, String status) {
        AgentRunRecord run = new AgentRunRecord();
        run.setId(21L);
        run.setRunId(id);
        run.setTurnId(11L);
        run.setStatus(status);
        run.setVersion(0);
        return run;
    }

    private AgentTurnRecord turn() {
        AgentTurnRecord turn = new AgentTurnRecord();
        turn.setId(11L);
        turn.setTurnId("turn-1");
        turn.setSessionId(7L);
        turn.setStatus("RUNNING");
        return turn;
    }
}
