package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.mapper.AgentRunMapper;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.AgentTurnMapper;
import com.research.assistant.service.agent.core.AgentModelCallTrace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentRuntimeServiceTest {

    @Autowired private AgentRuntimeService service;
    @Autowired private AgentTurnMapper turnMapper;
    @Autowired private AgentRunMapper runMapper;
    @Autowired private AgentToolCallMapper toolCallMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void persistsIdempotentTurnWaitingResumeToolAndCompletion() {
        jdbcTemplate.update("INSERT INTO research_session "
                        + "(id, session_key, title, session_type, mode, output_language, archived) "
                        + "VALUES (?, ?, ?, 'SINGLE', 'analysis', 'ZH', FALSE)",
                93001L, "agent-session", "Agent session");

        AgentTurnRecord turn = service.createTurn(93001L, "request-1", "user-message-1");
        AgentTurnRecord duplicate = service.createTurn(93001L, "request-1", "user-message-1");
        assertThat(duplicate.getId()).isEqualTo(turn.getId());
        assertThatThrownBy(() -> service.createTurn(93001L, "request-2", "user-message-2"))
                .isInstanceOf(AgentRuntimeConflictException.class);

        AgentRunRecord run = service.startRun(turn.getTurnId(),
                new AgentModelSnapshot("settings-v1", "a".repeat(64),
                        "{\"provider\":\"test\",\"model\":\"fake\"}"),
                new AgentRunBudget(4, 6, 1000, 30_000),
                "agent-context-v1", "{}", "b".repeat(64), "parser-v1");
        assertThat(turnMapper.selectByTurnId(turn.getTurnId()).getStatus()).isEqualTo("RUNNING");

        service.transitionRun(run.getRunId(), AgentRunStatus.WAITING_USER, null, null, null);
        assertThat(runMapper.selectByRunId(run.getRunId()).getStatus()).isEqualTo("WAITING_USER");
        service.transitionRun(run.getRunId(), AgentRunStatus.RUNNING, null, null, null);

        AgentToolCallRecord call = service.registerToolCall(run.getRunId(), "read_source",
                "{\"sourceObjectId\":\"source-1\"}", true, "tool-key-1");
        AgentToolCallRecord repeated = service.registerToolCall(run.getRunId(), "read_source",
                "{\"sourceObjectId\":\"source-1\"}", true, "tool-key-1");
        assertThat(repeated.getId()).isEqualTo(call.getId());
        assertThatThrownBy(() -> service.registerToolCall(run.getRunId(), "read_pages",
                "{\"page\":1}", true, "tool-key-1"))
                .isInstanceOf(AgentRuntimeConflictException.class);

        service.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING, null, null, null);
        service.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                "{\"content\":\"ok\"}", null, null);
        assertThat(toolCallMapper.selectByToolCallId(call.getToolCallId()).getAttemptCount()).isEqualTo(1);

        service.transitionRun(run.getRunId(), AgentRunStatus.COMPLETED,
                "{\"answer\":\"done\"}", null, null);
        assertThat(turnMapper.selectByTurnId(turn.getTurnId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(service.createTurn(93001L, "request-2", "user-message-2").getSequenceNo()).isEqualTo(2);
    }

    @Test
    void modelSnapshotRejectsCredentialFields() {
        assertThatThrownBy(() -> new AgentModelSnapshot(
                "settings-v1", "a".repeat(64), "{\"apiKey\":\"secret\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsFrameworkUsageWithoutTurningMetricsIntoAFailureGate() {
        jdbcTemplate.update("INSERT INTO research_session "
                        + "(id, session_key, title, session_type, mode, output_language, archived) "
                        + "VALUES (?, ?, ?, 'SINGLE', 'analysis', 'ZH', FALSE)",
                93002L, "budget-session", "Budget session");
        AgentTurnRecord turn = service.createTurn(93002L, "request-budget", "message");
        AgentRunRecord run = service.startRun(turn.getTurnId(),
                new AgentModelSnapshot("settings-v1", "b".repeat(64), "{\"model\":\"fake\"}"),
                new AgentRunBudget(1, 1, 10, 30_000), "agent-context-v1", "{}", null, null);

        service.recordUsage(run.getRunId(), 2, 3, 40, 5);
        service.recordModelCall(run.getRunId(), new AgentModelCallTrace(
                1, "COMPLETED", 120, 4, 3, 30, 5, "STOP", null));
        AgentRunRecord recorded = service.getRun(run.getRunId());
        assertThat(recorded.getModelCalls()).isEqualTo(2);
        assertThat(recorded.getToolCalls()).isEqualTo(3);
        assertThat(recorded.getPromptTokens()).isEqualTo(40);
        assertThat(recorded.getCompletionTokens()).isEqualTo(5);
        assertThat(recorded.getModelTraceJson()).contains("\"durationMs\":120", "\"finishReason\":\"STOP\"");
    }
}
