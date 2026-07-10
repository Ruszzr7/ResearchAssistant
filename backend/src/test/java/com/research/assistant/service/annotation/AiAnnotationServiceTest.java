package com.research.assistant.service.annotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AiAnnotationServiceTest {

    private PaperAnalysisMapper paperAnalysisMapper;
    private PaperMapper paperMapper;
    private AnnotationService annotationService;
    private LLMService llmService;
    private AiAnnotationService service;

    @BeforeEach
    void setUp() {
        paperAnalysisMapper = mock(PaperAnalysisMapper.class);
        paperMapper = mock(PaperMapper.class);
        annotationService = mock(AnnotationService.class);
        llmService = mock(LLMService.class);
        service = new AiAnnotationService(paperAnalysisMapper, paperMapper, annotationService, llmService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "pdfStorageDir", "./data/papers");
    }

    @Test
    void shouldThrowWhenNoAnalysis() {
        when(paperAnalysisMapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.generateAndSave(1L));
    }

    @Test
    void shouldCreateAiAnnotationsFromLlmResponse() {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(1L);
        analysis.setCoreContribution("我们提出了一种新方法");
        analysis.setMethodSummary("方法细节");
        when(paperAnalysisMapper.selectOne(any())).thenReturn(analysis);

        String json = """
                [{"category":"METHOD","anchorText":"新方法","note":"核心方法"},
                 {"category":"ISSUE","anchorText":"局限","note":"训练成本高"}]
                """;
        when(llmService.chat(anyString(), anyString())).thenReturn(json);

        AnnotationDto saved = new AnnotationDto();
        saved.setId(100L);
        saved.setType("HIGHLIGHT");
        when(annotationService.create(eq(1L), any(), eq(true))).thenReturn(saved);

        List<AnnotationDto> result = service.generateAndSave(1L);

        assertThat(result).hasSize(2);
        verify(annotationService, times(2)).create(eq(1L), any(), eq(true));
    }

    @Test
    void shouldReturnEmptyWhenLlmReturnsInvalidJson() {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(1L);
        when(paperAnalysisMapper.selectOne(any())).thenReturn(analysis);
        when(llmService.chat(anyString(), anyString())).thenReturn("not json");

        List<AnnotationDto> result = service.generateAndSave(1L);

        assertThat(result).isEmpty();
    }
}
