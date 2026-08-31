package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentSelectedContent;
import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.entity.ResearchSession;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.service.agent.runtime.AgentConversationSummaryService;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import com.research.assistant.service.agent.source.PaperAgentReadinessService;
import com.research.assistant.service.agent.source.PaperAgentReadinessView;
import com.research.assistant.service.agent.source.PaperUnderstandingNotReadyException;
import com.research.assistant.service.agent.source.SourceContentType;
import com.research.assistant.service.agent.source.SourceObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentContextAssemblerTest {
    @Test
    void loadsHistoryOnlyFromCurrentConversation() {
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentConversationSummaryService summaries = mock(AgentConversationSummaryService.class);
        PaperMemoryMapper memories = mock(PaperMemoryMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        AgentContextAssembler assembler = new AgentContextAssembler(sessions, messages, summaries, memories,
                sources, new ObjectMapper());
        ResearchSession session = new ResearchSession(); session.setId(7L); session.setPrimaryPaperId(null);
        when(sessions.selectById(7L)).thenReturn(session);
        ResearchMessage prior = new ResearchMessage(); prior.setRole("USER"); prior.setContent("only-session-seven");
        when(messages.selectFinalAfter(7L, 0)).thenReturn(List.of(prior));

        AgentContextSnapshot result = assembler.assemble(input(7L, null));

        assertThat(result.messages()).extracting(AgentChatEntry::content).contains("only-session-seven");
        assertThat(result.messages().get(0).content())
                .contains("GitHub-flavored Markdown", "$...$", "$$...$$", "###")
                .contains("never use bracketed headings", "Do not emit unwrapped pseudo-LaTeX")
                .contains("capability descriptions are the authoritative usage contract")
                .contains("Ground paper-dependent factual claims in validated paper context")
                .contains("If available paper context is insufficient, state the limitation plainly");
        verify(messages).selectFinalAfter(7L, 0);
    }

    @Test
    void rehydratesRecentPaperReadsAndMakesOnlyCurrentSourcesCitable() {
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentConversationSummaryService summaries = mock(AgentConversationSummaryService.class);
        PaperMemoryMapper memories = mock(PaperMemoryMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        AgentToolCallMapper toolCalls = mock(AgentToolCallMapper.class);
        ResearchSession session = new ResearchSession(); session.setId(7L); session.setPrimaryPaperId(9L);
        when(sessions.selectById(7L)).thenReturn(session);
        SourceObject source = new SourceObject("src-1", 9L, "hash", "parser", 1,
                SourceContentType.TEXT, "论文原文证据", null, List.of("Results"), "", Map.of());
        PaperSourceCatalog catalog = new PaperSourceCatalog(9L, "hash", "parser", 1,
                Map.of("src-1", source), Map.of());
        when(sources.latest(9L)).thenReturn(catalog);
        when(messages.selectFinalAfter(7L, 0)).thenReturn(List.of());
        AgentToolCallRecord historical = new AgentToolCallRecord();
        historical.setToolCallId("tool-1");
        historical.setToolName("retrieve_paper_evidence");
        historical.setArgumentsJson("{\"searches\":[{\"query\":\"准确率\"}]}");
        historical.setResultJson("{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"src-1\",\"content\":\"论文原文证据\"}]}");
        when(toolCalls.selectRecentCompletedPaperReads(7L, "hash", "parser", 8))
                .thenReturn(List.of(historical));

        AgentContextAssembler assembler = new AgentContextAssembler(sessions, messages, summaries, memories,
                sources, new ObjectMapper(), null, null, null, toolCalls);

        AgentContextSnapshot result = assembler.assemble(input(7L, null));

        assertThat(result.messages()).extracting(AgentChatEntry::content)
                .anyMatch(content -> content.contains("Historical paper capability result")
                        && content.contains("论文原文证据"));
        assertThat(result.preReadSourceIds()).containsExactly("src-1");
        assertThat(result.snapshotJson()).contains("\"rehydratedPaperReadCount\":1",
                "\"rehydratedSourceCount\":1");
    }

    @Test
    void rejectsSelectionFromStalePdfVersion() {
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentConversationSummaryService summaries = mock(AgentConversationSummaryService.class);
        PaperMemoryMapper memories = mock(PaperMemoryMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        AgentContextAssembler assembler = new AgentContextAssembler(sessions, messages, summaries, memories,
                sources, new ObjectMapper());
        ResearchSession session = new ResearchSession(); session.setId(7L); session.setPrimaryPaperId(9L);
        when(sessions.selectById(7L)).thenReturn(session);
        when(sources.latest(9L)).thenReturn(new PaperSourceCatalog(9L, "new-hash", "parser", 1, Map.of(), Map.of()));
        AgentSelectedContent stale = new AgentSelectedContent("sel", 9, "old-hash", 1, "TEXT", "text", List.of());

        assertThatThrownBy(() -> assembler.assemble(input(7L, stale)))
                .hasMessageContaining("stale");
    }

    @Test
    void rejectsConversationUntilManualPaperUnderstandingIsReady() {
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentConversationSummaryService summaries = mock(AgentConversationSummaryService.class);
        PaperMemoryMapper memories = mock(PaperMemoryMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        PaperAgentReadinessService readiness = mock(PaperAgentReadinessService.class);
        ResearchSession session = new ResearchSession(); session.setId(7L); session.setPrimaryPaperId(9L);
        when(sessions.selectById(7L)).thenReturn(session);
        when(readiness.status(9L)).thenReturn(new PaperAgentReadinessView(9, "NOT_STARTED", "等待开始论文理解",
                true, false, false, false, false, false, false, 0));
        AgentContextAssembler assembler = new AgentContextAssembler(sessions, messages, summaries, memories,
                sources, new ObjectMapper(), null, readiness);

        assertThatThrownBy(() -> assembler.assemble(input(7L, null)))
                .isInstanceOf(PaperUnderstandingNotReadyException.class);
    }

    private AgentTurnInput input(long sessionId, AgentSelectedContent selection) {
        return new AgentTurnInput(sessionId, null, "question", null, selection, List.of(), List.of(),
                null, "req", null);
    }
}
