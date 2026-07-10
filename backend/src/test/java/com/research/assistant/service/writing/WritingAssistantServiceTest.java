package com.research.assistant.service.writing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.*;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.rag.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class WritingAssistantServiceTest {

    private LLMService llmService;
    private SettingsService settingsService;
    private PaperMapper paperMapper;
    private PaperAnalysisMapper paperAnalysisMapper;
    private RagRetrievalService ragRetrievalService;
    private ObjectMapper objectMapper;
    private WritingAssistantService service;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        settingsService = mock(SettingsService.class);
        paperMapper = mock(PaperMapper.class);
        paperAnalysisMapper = mock(PaperAnalysisMapper.class);
        ragRetrievalService = mock(RagRetrievalService.class);
        objectMapper = new ObjectMapper();
        service = new WritingAssistantService(llmService, settingsService, paperMapper,
                paperAnalysisMapper, ragRetrievalService, objectMapper);
    }

    @Test
    void generateOutlineShouldParseSections() {
        when(settingsService.getValue("research_topic")).thenReturn("多模态大模型");
        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"sections":[{"level":1,"title":"Introduction","children":[{"level":2,"title":"Background"}]}]}
                """);

        OutlineDto dto = service.generateOutline("医疗影像分割", "学术", "中文");

        assertThat(dto.getSections()).hasSize(1);
        assertThat(dto.getSections().get(0).getTitle()).isEqualTo("Introduction");
        assertThat(dto.getSections().get(0).getChildren()).hasSize(1);
    }

    @Test
    void generateRelatedWorkShouldRequirePaperIds() {
        assertThrows(IllegalArgumentException.class,
                () -> service.generateRelatedWork(List.of(), "topic", null));
    }

    @Test
    void generateRelatedWorkShouldReturnContentAndCitations() {
        when(settingsService.getValue("research_topic")).thenReturn("");
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(1L);
        analysis.setCoreContribution("核心贡献");
        analysis.setMethodSummary("方法概述");
        when(paperAnalysisMapper.selectList(any())).thenReturn(List.of(analysis));

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("Test Paper");
        when(paperMapper.selectById(1L)).thenReturn(paper);

        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"content":"相关工作段落 [1]。","citations":[{"paperId":1,"placeholder":"[1]","sentence":"相关工作段落 [1]。"}]}
                """);

        RelatedWorkDto dto = service.generateRelatedWork(List.of(1L), "topic", null);

        assertThat(dto.getContent()).contains("相关工作段落");
        assertThat(dto.getCitations()).hasSize(1);
        assertThat(dto.getCitations().get(0).getPaperId()).isEqualTo(1L);
    }

    @Test
    void checkCitationsShouldReturnSuggestionsAndConflicts() {
        when(ragRetrievalService.retrieveAndRerank(anyString(), anyInt(), anyDouble())).thenReturn(List.of());
        when(paperAnalysisMapper.selectList(any())).thenReturn(List.of());

        when(llmService.chat(anyString(), anyString())).thenReturn("""
                {"suggestions":[{"paperId":1,"reason":"方法相关","position":"第一句后"}],
                 "conflicts":[{"paperId":2,"type":"DUPLICATE","reason":"观点重复"}]}
                """);

        CitationCheckDto dto = service.checkCitations("我们提出了一种新方法。", List.of());

        assertThat(dto.getSuggestions()).hasSize(1);
        assertThat(dto.getSuggestions().get(0).getPosition()).isEqualTo("第一句后");
        assertThat(dto.getConflicts()).hasSize(1);
        assertThat(dto.getConflicts().get(0).getType()).isEqualTo("DUPLICATE");
    }

    @Test
    void checkCitationsShouldRejectEmptyParagraph() {
        assertThrows(IllegalArgumentException.class,
                () -> service.checkCitations("  ", List.of()));
    }
}
