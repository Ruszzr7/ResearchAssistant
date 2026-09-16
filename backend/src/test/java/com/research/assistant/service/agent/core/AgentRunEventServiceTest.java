package com.research.assistant.service.agent.core;

import com.research.assistant.dto.agent.AgentRunEvent;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.AgentRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunEventServiceTest {
    @Test
    void pollingAndReconnectUseStableIncrementingEventIds() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        when(loop.currentResult("run-1")).thenReturn(new AgentTurnResult("turn-1", "run-1", "COMPLETED",
                "answer", List.of(), List.of()));
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setToolCallId("tool-1"); call.setToolName("read_source"); call.setStatus("COMPLETED");
        when(calls.selectByRunId("run-1")).thenReturn(List.of(call));
        AgentRunEventService service = new AgentRunEventService(loop, calls, new ObjectMapper());

        List<AgentRunEvent> all = service.events("run-1", 0);
        List<AgentRunEvent> reconnected = service.events("run-1", 2);

        assertThat(all).extracting(AgentRunEvent::id).containsExactly(1L, 2L, 3L);
        assertThat(all).extracting(AgentRunEvent::type)
                .containsExactly("run.status", "tool.completed", "message.final");
        assertThat(reconnected).extracting(AgentRunEvent::id).containsExactly(3L);
    }

    @Test
    void exposesClientActionAsARecoverableEvent() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        when(loop.currentResult("run-2")).thenReturn(new AgentTurnResult("turn-2", "run-2", "WAITING_CLIENT",
                "正在执行页面操作。", List.of(), List.of()));
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setToolCallId("tool-2"); call.setToolName("paper_action"); call.setStatus("WAITING_CLIENT");
        when(calls.selectByRunId("run-2")).thenReturn(List.of(call));

        List<AgentRunEvent> events = new AgentRunEventService(loop, calls, new ObjectMapper()).events("run-2", 0);

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("run.status", "tool.requested", "action.required");
    }

    @Test
    void exposesBoundedEvidenceDiagnosticsWithoutPaperText() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        when(loop.currentResult("run-3")).thenReturn(new AgentTurnResult("turn-3", "run-3", "COMPLETED",
                "answer", List.of(), List.of()));
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setToolCallId("tool-3");
        call.setToolName("retrieve_paper_evidence");
        call.setStatus("COMPLETED");
        call.setResultJson("{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"s-1\",\"content\":\"secret\"}],"
                + "\"evidenceNeeds\":[{\"needId\":\"mechanism\",\"retrievalStatus\":\"found\",\"progress\":{\"outcome\":\"new_sources\",\"attempt\":1,\"refinementCount\":0,\"newSourceObjectIds\":[\"s-1\"],\"recommendedAction\":\"judge\",\"reason\":\"请判断\"}},"
                + "{\"retrievalStatus\":\"not_found\"}]}");
        when(calls.selectByRunId("run-3")).thenReturn(List.of(call));

        AgentRunEvent event = new AgentRunEventService(loop, calls, new ObjectMapper()).events("run-3", 0).get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.data();

        assertThat(data).containsEntry("resultStatus", "found")
                .containsEntry("evidenceNeedCount", 2)
                .containsEntry("evidenceNeedFoundCount", 1);
        assertThat(data.get("returnedSourceObjectIds")).isEqualTo(List.of("s-1"));
        assertThat(data.toString()).contains("evidenceNeedProgress", "mechanism", "new_sources", "请判断");
        assertThat(data).doesNotContainKeys("stopRecommended", "coverage", "newSourceCount");
        assertThat(data.toString()).doesNotContain("secret");
    }

    @Test
    void exposesEvidenceValidationCodesWithoutPromptOrPaperText() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        when(loop.currentResult("run-4")).thenReturn(new AgentTurnResult("turn-4", "run-4", "COMPLETED",
                "answer", List.of(), List.of()));
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setToolCallId("tool-4");
        call.setToolName("retrieve_paper_evidence");
        call.setStatus("COMPLETED");
        call.setResultJson("{\"status\":\"invalid_request\",\"sources\":[],\"issues\":["
                + "{\"needId\":\"result\",\"code\":\"MISSING_RETRIEVAL_ANCHOR\",\"message\":\"secret prompt\"}]}");
        when(calls.selectByRunId("run-4")).thenReturn(List.of(call));

        AgentRunEvent event = new AgentRunEventService(loop, calls, new ObjectMapper()).events("run-4", 0).get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.data();

        assertThat(data).containsEntry("resultStatus", "invalid_request")
                .containsEntry("validationIssueCount", 1);
        assertThat(data.get("validationIssueCodes")).isEqualTo(List.of("MISSING_RETRIEVAL_ANCHOR"));
        assertThat(data.toString()).doesNotContain("secret prompt");
    }

    @Test
    void exposesSkillNameForActivationDiagnostics() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        when(loop.currentResult("run-skill")).thenReturn(new AgentTurnResult("turn-skill", "run-skill", "COMPLETED",
                "answer", List.of(), List.of()));
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setToolCallId("tool-skill");
        call.setToolName("activate_skill");
        call.setStatus("COMPLETED");
        call.setResultJson("{\"skillName\":\"paper-evidence\",\"instructions\":\"private instructions\"}");
        when(calls.selectByRunId("run-skill")).thenReturn(List.of(call));

        AgentRunEvent event = new AgentRunEventService(loop, calls, new ObjectMapper()).events("run-skill", 0).get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.data();

        assertThat(data).containsEntry("skillName", "paper-evidence");
        assertThat(data.toString()).doesNotContain("private instructions");
    }

    @Test
    void exposesDurableFailureCodeForTimeoutDiagnostics() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(loop.currentResult("run-5")).thenReturn(new AgentTurnResult("turn-5", "run-5", "FAILED",
                "论文助手排队时间过长，请稍后重试", List.of(), List.of()));
        when(calls.selectByRunId("run-5")).thenReturn(List.of());
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-5");
        run.setStatus("FAILED");
        run.setErrorCode("QUEUE_TIMEOUT");
        run.setErrorMessage("agent run queue exceeded 90000 ms");
        when(runs.selectByRunId("run-5")).thenReturn(run);

        AgentRunEvent event = new AgentRunEventService(loop, calls, new ObjectMapper(), runs)
                .events("run-5", 0).stream()
                .filter(value -> "run.failed".equals(value.type())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.data();
        assertThat(data).containsEntry("errorCode", "QUEUE_TIMEOUT")
                .containsEntry("errorMessage", "agent run queue exceeded 90000 ms")
                .containsEntry("failureCategory", "PROJECT_SCHEDULER")
                .containsEntry("retryable", true);
    }

    @Test
    void infersHistoricalLocalGuardBehindAWatchdogTimeout() {
        AgentLoopService loop = mock(AgentLoopService.class);
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(loop.currentResult("run-6")).thenReturn(new AgentTurnResult("turn-6", "run-6", "FAILED",
                "模型响应超时，请稍后重试", List.of(), List.of()));
        when(calls.selectByRunId("run-6")).thenReturn(List.of());
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-6");
        run.setStatus("FAILED");
        run.setErrorCode("RUN_TIMEOUT");
        run.setErrorMessage("agent run exceeded 90000 ms");
        run.setMaxModelCalls(7);
        run.setModelTraceJson("[{\"ordinal\":8,\"status\":\"FAILED\","
                + "\"estimatedPromptTokens\":11000,\"responseKind\":\"NOT_SENT\"}]");
        when(runs.selectByRunId("run-6")).thenReturn(run);

        AgentRunEvent event = new AgentRunEventService(loop, calls, new ObjectMapper(), runs)
                .events("run-6", 0).stream()
                .filter(value -> "run.failed".equals(value.type())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.data();
        assertThat(data).containsEntry("errorCode", "RUN_TIMEOUT")
                .containsEntry("inferredErrorCode", "AGENT_CALL_LIMIT")
                .containsEntry("inferredMessage", "论文助手调用次数已达上限，请缩小问题范围后重试")
                .containsEntry("failureCategory", "PROJECT_LIMIT")
                .containsEntry("retryable", false);
    }
}
