package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.ai.LlmCallPolicy;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperWholePaperModelCallTest {

    @Test
    void shouldUseOneMultimodalRequestAndParseTheWholeProfile() {
        PaperMemoryModelClient client = mock(PaperMemoryModelClient.class);
        PaperWholeDocumentInputBuilder inputBuilder = mock(PaperWholeDocumentInputBuilder.class);
        PaperMemoryChunker chunker = new PaperMemoryChunker(1_000, 1_200);
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                17L, "a".repeat(64), "parser", 0.9, Instant.now(), 1,
                List.of(new DocumentBlock("b-1", 1,
                        new NormalizedBoundingBox(0.1, 0.1, 0.8, 0.1),
                        DocumentBlockRole.BODY, 1, List.of(), "unique source text",
                        null, null, 0.9)));
        PaperStructure structure = structure();
        when(inputBuilder.build(any(), any(), anyString())).thenReturn(
                new PaperWholeDocumentInputBuilder.PaperWholeDocumentInput(
                        "page-images", 1, 1,
                        List.of(TextContent.from("whole paper"),
                                ImageContent.from("aGVsbG8=", "image/jpeg")),
                        Map.of("p1-s0000", List.of("b-1"))));
        when(client.chat(anyString(), anyList(), any(LlmCallPolicy.class))).thenReturn(
                new LlmResponse("""
                        {"domain":"AI", "researchProblem":"Estimate the target robustly.",
                         "coreContributions":[{"category":"CONTRIBUTION","statement":"Introduces estimator E.",
                          "evidenceSpanIds":["p1-s0000"],"confidence":0.88}],
                         "methodType":"EXPERIMENTAL", "methodSummary":"Estimator E is evaluated.",
                         "datasets":[],"models":["E"],"metrics":[],"keyFindings":[],"limitations":[],
                         "experimentSetup":{},"benchmarkResults":[],"sectionDigests":[],"openQuestions":[]}
                        """, 100, 20, 120, "STOP"));

        PaperMemoryModelService service = new PaperMemoryModelService(
                client, new ObjectMapper().findAndRegisterModules(), inputBuilder, chunker);

        PaperMemoryModelService.WholePaperGeneration result = service.understandWhole(structure, artifact);

        assertThat(result.profile().researchProblem()).contains("Estimate");
        assertThat(result.profile().coreContributions()).singleElement()
                .extracting(PaperMemoryClaim::evidenceBlockIds)
                .isEqualTo(List.of("b-1"));
        assertThat(result.summary().promptTokens()).isEqualTo(100);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(inputBuilder).build(any(), any(), prompt.capture());
        assertThat(prompt.getValue()).contains("页面视觉内容提供");
        assertThat(prompt.getValue()).contains("evidenceSpanIds");
        assertThat(prompt.getValue())
                .contains("paper-memory-whole-v9-noncitable-captions")
                .contains("提交前逐条检查 keyFindings")
                .contains("找不到直接正文证据就删除该 finding");
        assertThat(prompt.getValue()).doesNotContain("unique source text");
        verify(client, times(1)).chat(systemPrompt.capture(), anyList(), any(LlmCallPolicy.class));
        assertThat(systemPrompt.getValue())
                .contains("PARAGRAPH 或 ABSTRACT span")
                .contains("AUXILIARY_CAPTION")
                .contains("没有可引用 ID");
    }

    private PaperStructure structure() {
        return new PaperStructure(PaperStructure.SCHEMA_VERSION, 17L,
                new PaperStructure.Source("a".repeat(64), "parser", 0.9, "parser"),
                new PaperStructure.Metadata("Paper", List.of(), 2026, "", "", List.of(), ""),
                1, List.of("b-1"), List.of(), List.of(), List.of(),
                new PaperStructure.Statistics(1, 1, 0, 11, Map.of(), Map.of()),
                new PaperStructure.Quality(0.9, 0.9, 0, 0, List.of()), Instant.now());
    }
}
