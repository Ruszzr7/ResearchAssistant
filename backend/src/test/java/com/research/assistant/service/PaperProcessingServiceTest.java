package com.research.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ai.PaperAnalysisResult;
import com.research.assistant.service.ai.ResearchAiService;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PaperProcessingService} 单元测试。
 */
class PaperProcessingServiceTest {

    private final PaperMapper paperMapper = mock(PaperMapper.class);
    private final PaperAnalysisMapper analysisMapper = mock(PaperAnalysisMapper.class);
    private final PdfExtractor pdfExtractor = mock(PdfExtractor.class);
    private final TextPreprocessor textPreprocessor = mock(TextPreprocessor.class);
    private final LLMService llmService = mock(LLMService.class);
    private final ResearchAiService researchAiService = mock(ResearchAiService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PaperProcessingService service = new PaperProcessingService(
            paperMapper, analysisMapper, pdfExtractor, textPreprocessor,
            llmService, researchAiService, objectMapper);

    @Test
    void processShouldUsePojoPathAndMapToEntity() {
        givenPaperWithPdf(1L, "paper.pdf");
        when(pdfExtractor.extract("paper.pdf")).thenReturn("raw text");
        when(textPreprocessor.clean("raw text")).thenReturn("cleaned text");
        when(textPreprocessor.truncate("cleaned text", 12000)).thenReturn("input text");
        when(analysisMapper.selectOne(any())).thenReturn(null);

        PaperAnalysisResult result = new PaperAnalysisResult();
        result.setDomain("AI");
        result.setCoreContribution("核心贡献");
        result.setMethodType("EXPERIMENTAL");
        result.setMethodSummary("方法概述");
        PaperAnalysisResult.Section section = new PaperAnalysisResult.Section();
        section.setHeading("Intro");
        section.setSummary("intro summary");
        result.setSections(List.of(section));
        result.setDatasets(List.of("CIFAR-10"));
        result.setModels(List.of("ResNet"));
        result.setKeyFindings(List.of("finding"));
        result.setLimitations(List.of("limitation"));

        when(researchAiService.analyzePaper("input text"))
                .thenReturn(Result.<PaperAnalysisResult>builder()
                        .content(result)
                        .tokenUsage(new TokenUsage(100, 50, 150))
                        .build());

        PaperAnalysis analysis = service.process(1L);

        assertThat(analysis.getCoreContribution()).isEqualTo("核心贡献");
        assertThat(analysis.getMethodType()).isEqualTo("AI|EXPERIMENTAL");
        assertThat(analysis.getMethodSummary()).isEqualTo("方法概述");
        assertThat(analysis.getSectionsJson()).contains("Intro");
        assertThat(analysis.getDatasetsJson()).contains("CIFAR-10");
        assertThat(analysis.getModelsJson()).contains("ResNet");
        assertThat(analysis.getRawText()).isEqualTo("input text");
        assertThat(analysis.getTokenUsed()).isEqualTo(150);
        verify(llmService, never()).chatWithUsage(any(), any());
        verify(analysisMapper).insert(analysis);
    }

    @Test
    void processShouldFallbackWhenPojoFails() {
        givenPaperWithPdf(2L, "paper.pdf");
        when(pdfExtractor.extract("paper.pdf")).thenReturn("raw text");
        when(textPreprocessor.clean("raw text")).thenReturn("cleaned text");
        when(textPreprocessor.truncate("cleaned text", 12000)).thenReturn("input text");
        when(analysisMapper.selectOne(any())).thenReturn(null);

        when(researchAiService.analyzePaper("input text"))
                .thenThrow(new RuntimeException("POJO 解析失败"));

        String json = """
                {
                  "domain": "CV",
                  "core_contribution": "fallback contribution",
                  "method_type": "THEORETICAL",
                  "method_summary": "fallback summary",
                  "sections": [],
                  "datasets": ["MNIST"],
                  "models": ["VGG"],
                  "key_findings": [],
                  "limitations": [],
                  "tables_summary": [],
                  "figures_summary": []
                }
                """;
        when(llmService.chatWithUsage(any(), eq("input text")))
                .thenReturn(new LlmResponse(json, 10, 20, 30));

        PaperAnalysis analysis = service.process(2L);

        assertThat(analysis.getCoreContribution()).isEqualTo("fallback contribution");
        assertThat(analysis.getMethodType()).isEqualTo("CV|THEORETICAL");
        assertThat(analysis.getDatasetsJson()).contains("MNIST");
        assertThat(analysis.getModelsJson()).contains("VGG");
        assertThat(analysis.getTokenUsed()).isEqualTo(30);
        verify(analysisMapper).insert(analysis);
    }

    private void givenPaperWithPdf(Long id, String pdfPath) {
        Paper paper = new Paper();
        paper.setId(id);
        paper.setPdfPath(pdfPath);
        when(paperMapper.selectById(id)).thenReturn(paper);
    }
}
