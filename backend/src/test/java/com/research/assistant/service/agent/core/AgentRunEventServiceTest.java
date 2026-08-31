package com.research.assistant.service.agent.core;

import com.research.assistant.dto.agent.AgentRunEvent;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
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
                + "\"evidenceNeeds\":[{\"status\":\"found\"},{\"status\":\"not_found\"}]}");
        when(calls.selectByRunId("run-3")).thenReturn(List.of(call));

        AgentRunEvent event = new AgentRunEventService(loop, calls, new ObjectMapper()).events("run-3", 0).get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.data();

        assertThat(data).containsEntry("resultStatus", "found")
                .containsEntry("evidenceNeedCount", 2)
                .containsEntry("evidenceNeedFoundCount", 1);
        assertThat(data.get("returnedSourceObjectIds")).isEqualTo(List.of("s-1"));
        assertThat(data.toString()).doesNotContain("secret");
    }
}
