package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentSelectedContent;
import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.entity.AgentConversationSummaryRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.entity.ResearchSession;
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
        ResearchMessage reply = new ResearchMessage(); reply.setRole("ASSISTANT"); reply.setContent("session-seven-answer");
        when(messages.selectFinalAfter(7L, 0)).thenReturn(List.of(prior, reply));

        AgentContextSnapshot result = assembler.assemble(input(7L, null));

        assertThat(result.messages()).extracting(AgentChatEntry::content).contains("only-session-seven");
        assertThat(result.messages().get(0).content())
                .contains("GitHub 风格 Markdown", "$...$", "$$...$$", "###")
                .contains("不要使用【标题】这类方括号标题", "不要输出未包裹的伪 LaTeX")
                .contains("能力描述是使用规则的权威来源")
                .contains("根据答案的实际依据选择 groundingMode")
                .contains("论文画像只用于确定方向和设计 Need")
                .contains("如果现有论文上下文不足，只回答已经确认的内容")
                .doesNotContain("证据限制");
        verify(messages).selectFinalAfter(7L, 0);
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

    @Test
    void keepsStructuredSummaryAndRecentAnswerSourceHandlesButDoesNotMakeThemPreRead() {
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentConversationSummaryService summaries = mock(AgentConversationSummaryService.class);
        PaperMemoryMapper memories = mock(PaperMemoryMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        ResearchSession session = new ResearchSession(); session.setId(7L); session.setPrimaryPaperId(9L);
        when(sessions.selectById(7L)).thenReturn(session);
        SourceObject source = new SourceObject("src-result", 9L, "hash", "parser", 1,
                SourceContentType.TEXT, "实验结果", null, List.of("Results"), "", Map.of());
        when(sources.latest(9L)).thenReturn(new PaperSourceCatalog(9L, "hash", "parser", 1,
                Map.of(source.sourceObjectId(), source), Map.of()));
        AgentConversationSummaryRecord summary = new AgentConversationSummaryRecord();
        summary.setRevision(2);
        summary.setCoveredThroughMessageId(10L);
        summary.setSummaryJson("{\"currentGoal\":[\"核对实验\"],\"userPreferences\":[],"
                + "\"confirmedConclusions\":[],\"rejectedOrCorrectedConclusions\":[],"
                + "\"referencedObjects\":[],\"unresolvedQuestions\":[]}");
        when(summaries.latest(7L)).thenReturn(summary);
        ResearchMessage user = message("USER", "上轮问题");
        ResearchMessage assistant = message("ASSISTANT", "上轮回答");
        assistant.setEvidenceJson("{\"evidence\":[{\"sourceObjectId\":\"src-result\"}]}");
        when(messages.selectFinalAfter(7L, 10L)).thenReturn(List.of(user, assistant));
        AgentContextAssembler assembler = new AgentContextAssembler(sessions, messages, summaries, memories,
                sources, new ObjectMapper());

        AgentContextSnapshot result = assembler.assemble(input(7L, null));

        assertThat(result.messages()).extracting(AgentChatEntry::content)
                .anyMatch(content -> content.contains("核对实验"))
                .anyMatch(content -> content.contains("上轮回答") && content.contains("src-result")
                        && content.contains("重新引用前必须再次读取"));
        assertThat(result.preReadSourceIds()).isEmpty();
    }

    @Test
    void excludesPreviousRunSkillProfileEvidenceAndToolTranscriptFromTheNextRun() {
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentConversationSummaryService summaries = mock(AgentConversationSummaryService.class);
        PaperMemoryMapper memories = mock(PaperMemoryMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        ResearchSession session = new ResearchSession();
        session.setId(7L);
        when(sessions.selectById(7L)).thenReturn(session);

        ResearchMessage previousUser = message("USER", "上一轮问题");
        ResearchMessage previousAssistant = message("ASSISTANT", "上一轮最终回答");
        previousAssistant.setEvidenceJson("{\"evidence\":[{\"sourceObjectId\":\"src-old\","
                + "\"content\":\"FULL_EVIDENCE_BODY\"}]}");
        ResearchMessage runStatus = message("SYSTEM", "FULL_SKILL_INSTRUCTIONS FULL_PROFILE_BODY "
                + "FULL_TOOL_JSON");
        runStatus.setMessageType("RUN_STATUS");
        when(messages.selectFinalAfter(7L, 0)).thenReturn(List.of(previousUser, previousAssistant, runStatus));
        AgentContextAssembler assembler = new AgentContextAssembler(sessions, messages, summaries, memories,
                sources, new ObjectMapper());

        AgentContextSnapshot result = assembler.assemble(input(7L, null));
        String modelContext = result.messages().toString();

        assertThat(modelContext).contains("上一轮最终回答")
                .doesNotContain("FULL_SKILL_INSTRUCTIONS", "FULL_PROFILE_BODY", "FULL_EVIDENCE_BODY",
                        "FULL_TOOL_JSON");
    }

    @Test
    void rejectsWhenTheMandatoryLatestTurnWouldExceedTheHardLimit() {
        ResearchSessionMapper sessions = mock(ResearchSessionMapper.class);
        ResearchMessageMapper messages = mock(ResearchMessageMapper.class);
        AgentConversationSummaryService summaries = mock(AgentConversationSummaryService.class);
        PaperMemoryMapper memories = mock(PaperMemoryMapper.class);
        PaperSourceCatalogService sources = mock(PaperSourceCatalogService.class);
        ResearchSession session = new ResearchSession(); session.setId(7L);
        when(sessions.selectById(7L)).thenReturn(session);
        when(messages.selectFinalAfter(7L, 0)).thenReturn(List.of(
                message("USER", "old question"), message("ASSISTANT", "x".repeat(60_000))));
        AgentContextAssembler assembler = new AgentContextAssembler(sessions, messages, summaries, memories,
                sources, new ObjectMapper());

        assertThatThrownBy(() -> assembler.assemble(input(7L, null)))
                .hasMessage("最近一轮对话内容过长");
    }

    private static ResearchMessage message(String role, String content) {
        ResearchMessage message = new ResearchMessage();
        message.setRole(role);
        message.setMessageType("CHAT");
        message.setContent(content);
        return message;
    }

    private AgentTurnInput input(long sessionId, AgentSelectedContent selection) {
        return new AgentTurnInput(sessionId, null, "question", null, selection, List.of(), List.of(),
                null, "req", null);
    }
}
