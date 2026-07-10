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
import static org.mockito.ArgumentMatchers.eq;
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
    private final SettingsService settingsService = mock(SettingsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AsyncTaskService asyncTaskService = mock(AsyncTaskService.class);

    private final PaperProcessingService service = new PaperProcessingService(
            paperMapper, analysisMapper, pdfExtractor, textPreprocessor,
            llmService, researchAiService, settingsService, objectMapper, asyncTaskService);

    @Test
    void processShouldUsePojoPathAndMapToEntity() {
        givenPaperWithPdf(1L, "paper.pdf");
        when(pdfExtractor.extract("paper.pdf")).thenReturn("raw text");
        when(textPreprocessor.clean("raw text")).thenReturn("cleaned text");
        when(textPreprocessor.truncate("cleaned text", 12000)).thenReturn("input text");
        when(analysisMapper.selectOne(any())).thenReturn(null);
        when(settingsService.getValue("research_topic")).thenReturn("transformer 模型压缩");

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

        PaperAnalysisResult.ReproducibleArtifact artifact = new PaperAnalysisResult.ReproducibleArtifact();
        artifact.setType("FORMULA");
        artifact.setTitle("Eq. (1)");
        artifact.setContent("\\alpha = \\beta + \\gamma");
        artifact.setLocation("Section 3.1");
        result.setReproducibleArtifacts(List.of(artifact));

        PaperAnalysisResult.ExperimentSetup setup = new PaperAnalysisResult.ExperimentSetup();
        setup.setTaskDefinition("图像分类");
        setup.setDatasets(List.of("CIFAR-10"));
        setup.setBaselines(List.of("ResNet-18"));
        setup.setMetrics(List.of("Top-1 Accuracy"));
        setup.setImplementationDetails("PyTorch, 8x A100");
        result.setExperimentSetup(setup);

        PaperAnalysisResult.BenchmarkResult benchmark = new PaperAnalysisResult.BenchmarkResult();
        benchmark.setMetric("Top-1 Accuracy");
        benchmark.setValue("92.3%");
        benchmark.setBaselineValue("90.1%");
        benchmark.setDataset("CIFAR-10");
        benchmark.setSource("Table 3");
        benchmark.setNote("SOTA");
        result.setBenchmarkResults(List.of(benchmark));

        result.setRelevanceScore(8);
        result.setRelevanceReason("与模型压缩方向直接相关");

        when(researchAiService.analyzePaper("input text", "transformer 模型压缩"))
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
        assertThat(analysis.getReproducibleArtifactsJson()).contains("Eq. (1)");
        assertThat(analysis.getExperimentSetupJson()).contains("图像分类");
        assertThat(analysis.getBenchmarkResultsJson()).contains("92.3%");
        assertThat(analysis.getRelevanceScore()).isEqualTo(8);
        assertThat(analysis.getRelevanceReason()).isEqualTo("与模型压缩方向直接相关");
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
        when(settingsService.getValue("research_topic")).thenReturn("");

        when(researchAiService.analyzePaper("input text", ""))
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
                  "figures_summary": [],
                  "reproducible_artifacts": [
                    {"type": "PSEUDOCODE", "title": "Algorithm 1", "content": "step 1...", "location": "Section 2"}
                  ],
                  "experiment_setup": {
                    "task_definition": "分类",
                    "datasets": ["MNIST"],
                    "baselines": ["LeNet"],
                    "metrics": ["Accuracy"],
                    "implementation_details": "PyTorch"
                  },
                  "benchmark_results": [
                    {"metric": "Accuracy", "value": "99.1%", "baseline_value": "98.9%", "dataset": "MNIST", "source": "Table 1", "note": ""}
                  ],
                  "relevance_score": null,
                  "relevance_reason": null
                }
                """;
        when(llmService.chatWithUsage(any(), eq("用户当前研究主题：\n\n请对以下论文文本进行结构化分析：\n\ninput text")))
                .thenReturn(new LlmResponse(json, 10, 20, 30));

        PaperAnalysis analysis = service.process(2L);

        assertThat(analysis.getCoreContribution()).isEqualTo("fallback contribution");
        assertThat(analysis.getMethodType()).isEqualTo("CV|THEORETICAL");
        assertThat(analysis.getDatasetsJson()).contains("MNIST");
        assertThat(analysis.getModelsJson()).contains("VGG");
        assertThat(analysis.getReproducibleArtifactsJson()).contains("Algorithm 1");
        assertThat(analysis.getExperimentSetupJson()).contains("分类");
        assertThat(analysis.getBenchmarkResultsJson()).contains("99.1%");
        assertThat(analysis.getRelevanceScore()).isNull();
        assertThat(analysis.getRelevanceReason()).isNull();
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
