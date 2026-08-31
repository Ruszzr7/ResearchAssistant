package com.research.assistant.service.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentActionReceiptRequest;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.entity.PaperAnnotation;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.PaperAnnotationMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import com.research.assistant.service.agent.source.SourceContentType;
import com.research.assistant.service.agent.source.SourceLocator;
import com.research.assistant.service.agent.source.SourceObject;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentActionReceiptServiceTest {
    @Test
    void duplicateSuccessfulReceiptCreatesAtMostOneAnnotation() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentToolCallMapper calls = mock(AgentToolCallMapper.class);
        PaperAnnotationMapper annotations = mock(PaperAnnotationMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setId(1L); call.setRunId("run-1"); call.setToolCallId("tool-1"); call.setVersion(0);
        call.setReadOnly(false); call.setStatus("RUNNING");
        when(calls.storeActionTicket(eq(1L), eq(0), anyString(), any())).thenAnswer(invocation -> {
            call.setActionTicketHash(invocation.getArgument(2)); call.setStatus("WAITING_CLIENT"); return 1;
        });
        when(calls.selectByToolCallId("tool-1")).thenReturn(call);
        when(calls.selectByRunId("run-1")).thenReturn(List.of(call));
        ActionTicketService tickets = new ActionTicketService(calls, json);
        ActionTarget target = new ActionTarget(9, "hash", "src", 2, List.of("loc"),
                List.of(new NormalizedBoundingBox(.1, .2, .3, .04)));
        var ticket = tickets.issue("run-1", call, PaperActionType.HIGHLIGHT, target, null, "#ffee58");
        when(sources.latest(9)).thenReturn(catalog());
        PaperAnnotation persisted = new PaperAnnotation(); persisted.setId(77L); persisted.setAgentToolCallId("tool-1");
        when(annotations.selectByAgentToolCallId("tool-1")).thenReturn(null, persisted, persisted);
        doAnswer(invocation -> { PaperAnnotation value = invocation.getArgument(0); value.setId(77L); return 1; })
                .when(annotations).insert(any(PaperAnnotation.class));
        doAnswer(invocation -> { call.setStatus("COMPLETED"); return call; })
                .when(runtime).transitionToolCall(eq("tool-1"), eq(AgentToolCallStatus.COMPLETED), anyString(),
                        org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        AgentRunRecord run = new AgentRunRecord(); run.setRunId("run-1"); run.setStatus("WAITING_CLIENT");
        when(runtime.getRun("run-1")).thenReturn(run);
        AgentTurnRecord turn = new AgentTurnRecord(); turn.setId(3L); turn.setTurnId("turn-1"); turn.setSessionId(5L);
        when(runtime.getTurnForRun("run-1")).thenReturn(turn);
        doAnswer(invocation -> { ResearchMessage value = invocation.getArgument(0); value.setId(88L); return 1; })
                .when(messages).insert(any(ResearchMessage.class));
        AgentActionReceiptService service = new AgentActionReceiptService(tickets, calls, annotations, sources,
                runtime, messages, json);
        AgentActionReceiptRequest receipt = new AgentActionReceiptRequest(ticket.ticket(), true, Map.of("page", 2), null);

        var first = service.accept(receipt);
        var duplicate = service.accept(receipt);

        assertThat(first.annotationId()).isEqualTo(77L);
        assertThat(duplicate.annotationId()).isEqualTo(77L);
        verify(annotations, times(1)).insert(any(PaperAnnotation.class));
    }

    private PaperSourceCatalog catalog() {
        SourceObject source = new SourceObject("src", 9, "hash", "parser", 1, SourceContentType.TEXT,
                "target", null, List.of(), "", Map.of());
        SourceLocator locator = new SourceLocator("loc", "src", 2, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .2, .3, .04)), "target", EvidenceLocator.Precision.TEXT_RANGE);
        return new PaperSourceCatalog(9, "hash", "parser", 3, Map.of("src", source), Map.of("src", List.of(locator)));
    }
}
