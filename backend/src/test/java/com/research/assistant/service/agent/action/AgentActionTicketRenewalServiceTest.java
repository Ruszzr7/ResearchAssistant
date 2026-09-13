package com.research.assistant.service.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentPendingAction;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.entity.ResearchSession;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentActionTicketRenewalServiceTest {

    @Test
    void renewsCurrentPaperActionToolUsingItsActionTypeArgument() {
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        AgentToolCallMapper toolCalls = mock(AgentToolCallMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        PaperActionResolver resolver = mock(PaperActionResolver.class);
        ActionTicketService tickets = mock(ActionTicketService.class);
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-1");
        run.setTurnId(11L);
        run.setStatus(AgentRunStatus.WAITING_CLIENT.name());
        run.setDocumentHash("a".repeat(64));
        AgentTurnRecord turn = new AgentTurnRecord();
        turn.setId(11L);
        turn.setSessionId(7L);
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setId(12L);
        call.setToolCallId("tool-1");
        call.setRunId("run-1");
        call.setToolName("paper_action");
        call.setStatus(AgentToolCallStatus.WAITING_CLIENT.name());
        call.setVersion(3);
        call.setArgumentsJson("{\"actionType\":\"HIGHLIGHT\",\"sourceObjectId\":\"src-1\",\"color\":\"#ffee58\"}");
        ResearchSession session = new ResearchSession();
        session.setId(7L);
        session.setPrimaryPaperId(5L);
        PaperSourceCatalog catalog = mock(PaperSourceCatalog.class);
        ActionTarget target = new ActionTarget(5L, "a".repeat(64), "src-1", 2,
                java.util.List.of("loc-1"), java.util.List.of(
                        new com.research.assistant.service.pdf.layout.NormalizedBoundingBox(.1, .2, .3, .04)));
        when(runtime.getRun("run-1")).thenReturn(run);
        when(runtime.getTurnForRun("run-1")).thenReturn(turn);
        when(toolCalls.selectByToolCallId("tool-1")).thenReturn(call);
        when(sessions.selectById(7L)).thenReturn(session);
        when(sources.latest(5L)).thenReturn(catalog);
        when(catalog.documentHash()).thenReturn("a".repeat(64));
        when(resolver.resolve(catalog, "src-1")).thenReturn(target);
        when(tickets.issue(eq("run-1"), eq(call), eq(PaperActionType.HIGHLIGHT), eq(target),
                eq(null), eq("#ffee58")))
                .thenReturn(new ActionTicketService.IssuedActionTicket(
                        "renewed-ticket", Instant.now().plusSeconds(60), null));

        AgentActionTicketRenewalService service = new AgentActionTicketRenewalService(
                runtime, toolCalls, sources, resolver, tickets, new ObjectMapper(), sessions);

        AgentPendingAction result = service.renew("run-1", "tool-1");

        assertThat(result.actionType()).isEqualTo(PaperActionType.HIGHLIGHT);
        assertThat(result.ticket()).isEqualTo("renewed-ticket");
        verify(tickets).issue(eq("run-1"), eq(call), eq(PaperActionType.HIGHLIGHT), eq(target),
                eq(null), eq("#ffee58"));
    }
}
