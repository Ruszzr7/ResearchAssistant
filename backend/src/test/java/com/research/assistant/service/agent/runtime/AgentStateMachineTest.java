package com.research.assistant.service.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AgentStateMachineTest {

    @Test
    void allowsWaitingResumeAndTerminalTransitions() {
        assertDoesNotThrow(() -> AgentStateMachine.requireRunTransition(
                AgentRunStatus.RUNNING, AgentRunStatus.WAITING_USER));
        assertDoesNotThrow(() -> AgentStateMachine.requireRunTransition(
                AgentRunStatus.WAITING_USER, AgentRunStatus.RUNNING));
        assertDoesNotThrow(() -> AgentStateMachine.requireRunTransition(
                AgentRunStatus.RUNNING, AgentRunStatus.COMPLETED));
    }

    @Test
    void rejectsTerminalResumeAndSkippedRunStates() {
        assertThatThrownBy(() -> AgentStateMachine.requireRunTransition(
                AgentRunStatus.COMPLETED, AgentRunStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AgentStateMachine.requireRunTransition(
                AgentRunStatus.QUEUED, AgentRunStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesToolCallLifecycle() {
        assertDoesNotThrow(() -> AgentStateMachine.requireToolTransition(
                AgentToolCallStatus.REQUESTED, AgentToolCallStatus.RUNNING));
        assertDoesNotThrow(() -> AgentStateMachine.requireToolTransition(
                AgentToolCallStatus.RUNNING, AgentToolCallStatus.WAITING_CLIENT));
        assertDoesNotThrow(() -> AgentStateMachine.requireToolTransition(
                AgentToolCallStatus.WAITING_CLIENT, AgentToolCallStatus.COMPLETED));
        assertThatThrownBy(() -> AgentStateMachine.requireToolTransition(
                AgentToolCallStatus.COMPLETED, AgentToolCallStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
    }
}
