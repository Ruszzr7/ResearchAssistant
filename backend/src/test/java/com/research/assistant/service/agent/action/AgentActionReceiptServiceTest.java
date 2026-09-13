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
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void keepsOrdinaryTextGeometryWhenFormulaUnderlineWasRefinedByPdfium() {
        SourceLocator formula = new SourceLocator("formula-loc", "eq:12", 5, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.13, .24, .36, .04)),
                "公式 (12)", EvidenceLocator.Precision.FORMULA_REGION);
        Map<String, Object> refined = Map.of(
                "geometryKind", "TEXT_RANGE",
                "rects", List.of(Map.of("x", .16, "y", .25, "width", .18, "height", .02)));

        Map<String, Object> coordinates = AgentActionReceiptService.coordinates(
                PaperActionType.UNDERLINE, List.of(formula), refined);

        assertThat(coordinates.get("geometryKind")).isEqualTo("TEXT_RANGE");
        assertThat(coordinates.get("source")).isEqualTo("AGENT_PDFIUM");
    }

    @Test
    void acceptsNaturalSelectionRowsBridgingParserGapsInsideTheTrustedColumn() {
        List<SourceLocator> locators = List.of(new SourceLocator("loc", "src", 7, "PDF_NORMALIZED",
                List.of(
                        new NormalizedBoundingBox(.08, .10, .40, .03),
                        new NormalizedBoundingBox(.09, .30, .38, .03)),
                "algorithm", EvidenceLocator.Precision.TEXT_RANGE));
        Map<String, Object> coordinates = Map.of(
                "page", 7,
                "rects", List.of(
                        Map.of("x", .09, "y", .105, "width", .37, "height", .015),
                        Map.of("x", .095, "y", .20, "width", .365, "height", .015),
                        Map.of("x", .09, "y", .305, "width", .36, "height", .015)));

        assertThatCode(() -> AgentActionReceiptService.validateClientCoordinates(
                coordinates, locators, PaperActionType.HIGHLIGHT)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNaturalSelectionRowsThatCrossIntoAnotherColumn() {
        List<SourceLocator> locators = List.of(new SourceLocator("loc", "src", 7, "PDF_NORMALIZED",
                List.of(
                        new NormalizedBoundingBox(.08, .10, .40, .03),
                        new NormalizedBoundingBox(.09, .30, .38, .03)),
                "algorithm", EvidenceLocator.Precision.TEXT_RANGE));
        Map<String, Object> coordinates = Map.of(
                "page", 7,
                "rects", List.of(
                        Map.of("x", .09, "y", .105, "width", .37, "height", .015),
                        Map.of("x", .55, "y", .20, "width", .38, "height", .015),
                        Map.of("x", .09, "y", .305, "width", .36, "height", .015)));

        assertThatThrownBy(() -> AgentActionReceiptService.validateClientCoordinates(
                coordinates, locators, PaperActionType.HIGHLIGHT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateSuccessfulReceiptCreatesAtMostOneAnnotation() throws Exception {
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
        AgentActionReceiptRequest receipt = new AgentActionReceiptRequest(ticket.ticket(), true, Map.of(
                "page", 2,
                "coordinateSpace", "PDF_NORMALIZED",
                // Simulate a PDFium range refined to a subset of the trusted source block.
                "rects", List.of(Map.of("x", .15, "y", .21, "width", .1, "height", .02))), null);

        var first = service.accept(receipt);
        var duplicate = service.accept(receipt);

        assertThat(first.annotationId()).isEqualTo(77L);
        assertThat(duplicate.annotationId()).isEqualTo(77L);
        ArgumentCaptor<PaperAnnotation> annotationCaptor = ArgumentCaptor.forClass(PaperAnnotation.class);
        verify(annotations, times(1)).insert(annotationCaptor.capture());
        var persistedCoordinates = json.readTree(annotationCaptor.getValue().getCoordinatesJson());
        assertThat(persistedCoordinates.path("coordinateSpace").asText()).isEqualTo("PDF_NORMALIZED");
        assertThat(persistedCoordinates.path("source").asText()).isEqualTo("AGENT_PDFIUM");
        assertThat(persistedCoordinates.path("anchorText").asText()).isEqualTo("target");
        assertThat(persistedCoordinates.path("quads").get(0).path("x1").asDouble()).isBetween(.14999, .15001);
        assertThat(persistedCoordinates.path("quads").get(0).path("x2").asDouble()).isBetween(.24999, .25001);
        assertThat(persistedCoordinates.path("quads").get(0).path("y1").asDouble()).isBetween(.22999, .23001);
        assertThat(persistedCoordinates.path("quads").get(0).path("y3").asDouble()).isBetween(.20999, .21001);
    }

    private PaperSourceCatalog catalog() {
        SourceObject source = new SourceObject("src", 9, "hash", "parser", 1, SourceContentType.TEXT,
                "target", null, List.of(), "", Map.of());
        SourceLocator locator = new SourceLocator("loc", "src", 2, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .2, .3, .04)), "target", EvidenceLocator.Precision.TEXT_RANGE);
        return new PaperSourceCatalog(9, "hash", "parser", 3, Map.of("src", source), Map.of("src", List.of(locator)));
    }
}
