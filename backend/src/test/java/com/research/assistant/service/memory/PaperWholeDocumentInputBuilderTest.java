package com.research.assistant.service.memory;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import com.research.assistant.service.pdf.layout.PaperSemanticSpanBuilder;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class PaperWholeDocumentInputBuilderTest {

    @Test
    void shouldSendOnlyRecoveryImagesWhenNativePdfIsUnavailableAndLayoutHasNoOrderIssue() throws Exception {
        Path root = Files.createTempDirectory("paper-whole-input");
        Path pdf = root.resolve("paper.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        PaperMapper paperMapper = mock(PaperMapper.class);
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        AiCapabilityService capability = mock(AiCapabilityService.class);
        when(capability.pdfReady()).thenReturn(false);

        PaperWholeDocumentInputBuilder builder = new PaperWholeDocumentInputBuilder(
                paperMapper, new PaperPdfFileResolver(root.toString()), capability);
        PaperLayoutArtifact artifact = artifact();

        PaperWholeDocumentInputBuilder.PaperWholeDocumentInput input = builder.build(
                structure(), artifact, "return JSON");

        assertThat(input.mode()).isEqualTo("page-images");
        assertThat(input.pageCount()).isEqualTo(2);
        assertThat(input.imageCount()).isZero();
        assertThat(input.recoveryImageCount()).isEqualTo(1);
        assertThat(input.contents()).anyMatch(content -> content instanceof ImageContent);
        assertThat(input.contents().stream().filter(content -> content instanceof ImageContent)).hasSize(1);
        assertThat(input.contents()).noneMatch(content -> content instanceof PdfFileContent);
        assertThat(input.contents().stream().map(Content::toString).toList())
                .anyMatch(value -> value.contains("p1-s0000") && value.contains("first page"));
        assertThat(input.contents().stream().map(Content::toString).toList())
                .anyMatch(value -> value.contains("AUXILIARY_CAPTION")
                        && value.contains("无证据ID") && value.contains("Figure 1"));
        assertThat(input.spanBlockIds()).containsEntry("p1-s0000", List.of("b1"));
        assertThat(input.spanBlockIds().values()).noneMatch(ids -> ids.contains("b3"));
        assertThat(input.recoveryRegions()).hasSize(1);
        assertThat(input.contents().stream().map(Content::toString).toList())
                .anyMatch(value -> value.contains("版面恢复图像"));
    }

    @Test
    void shouldIncludeNativePdfAndSourceTextWhenCapabilityIsVerified() throws Exception {
        Path root = Files.createTempDirectory("paper-whole-native");
        Path pdf = root.resolve("paper.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        PaperMapper paperMapper = mock(PaperMapper.class);
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        AiCapabilityService capability = mock(AiCapabilityService.class);
        when(capability.pdfReady()).thenReturn(true);

        PaperWholeDocumentInputBuilder builder = new PaperWholeDocumentInputBuilder(
                paperMapper, new PaperPdfFileResolver(root.toString()), capability);
        PaperWholeDocumentInputBuilder.PaperWholeDocumentInput input = builder.build(
                structure(), artifact(), "return JSON");

        assertThat(input.mode()).isEqualTo("native-pdf");
        assertThat(input.imageCount()).isZero();
        assertThat(input.recoveryImageCount()).isEqualTo(1);
        assertThat(input.contents()).anyMatch(content -> content instanceof PdfFileContent);
        assertThat(input.contents()).anyMatch(content -> content instanceof ImageContent);
    }

    @Test
    void shouldBoundRecoveryTextAndVisualPartsForLongDocuments() throws Exception {
        Path root = Files.createTempDirectory("paper-whole-bounded");
        Path pdf = root.resolve("paper.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        PaperMapper paperMapper = mock(PaperMapper.class);
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        AiCapabilityService capability = mock(AiCapabilityService.class);
        when(capability.pdfReady()).thenReturn(false);
        LayoutUncertainRegionDetector detector = mock(LayoutUncertainRegionDetector.class);
        LayoutUncertainRegion region = new LayoutUncertainRegion(
                "lr-p1-001", "VISUAL_CONTENT", List.of("b1"),
                List.of(new LayoutUncertainRegion.PageArea(1,
                        List.of(new NormalizedBoundingBox(.1, .1, .8, .1)))),
                "r".repeat(2_000));
        when(detector.detect(any(PaperLayoutArtifact.class))).thenReturn(List.of(region));

        PaperWholeDocumentInputBuilder builder = new PaperWholeDocumentInputBuilder(
                paperMapper, new PaperPdfFileResolver(root.toString()), capability,
                new PaperSemanticSpanBuilder(), detector);
        PaperWholeDocumentInputBuilder.PaperWholeDocumentInput input = builder.build(
                structure(), artifact(), "return JSON");

        String text = input.contents().stream()
                .filter(content -> content instanceof TextContent)
                .map(Object::toString)
                .reduce("", String::concat);
        assertThat(text).contains("区域文本已截断");
        assertThat(input.recoveryImageCount()).isEqualTo(1);
        assertThat(input.contents().stream().filter(content -> content instanceof ImageContent))
                .hasSize(1);
    }

    private PaperLayoutArtifact artifact() {
        return new PaperLayoutArtifact(7L, "a".repeat(64), "parser", 0.9,
                Instant.now(), 2, List.of(
                block("b1", 1, 1, "first page"),
                block("b2", 2, 2, "second page"),
                new DocumentBlock("b3", 2,
                        new NormalizedBoundingBox(0.1, 0.3, 0.8, 0.1),
                        DocumentBlockRole.CAPTION, 3, List.of(), "Figure 1. Result.",
                        null, null, 0.9),
                new DocumentBlock("b4", 2,
                        new NormalizedBoundingBox(0.2, 0.5, 0.6, 0.08),
                        DocumentBlockRole.FORMULA, 4, List.of(), "x", null, null,
                        0.55, DocumentBlockContentMode.REGION)));
    }

    private DocumentBlock block(String id, int page, int order, String text) {
        return new DocumentBlock(id, page,
                new NormalizedBoundingBox(0.1, 0.1, 0.8, 0.1),
                DocumentBlockRole.BODY, order, List.of(), text, null, null, 0.9);
    }

    private PaperStructure structure() {
        return new PaperStructure(PaperStructure.SCHEMA_VERSION, 7L,
                new PaperStructure.Source("a".repeat(64), "parser", 0.9, "parser"),
                new PaperStructure.Metadata("Paper", List.of(), 2026, "", "", List.of(), ""),
                2, List.of("b1", "b2"), List.of(), List.of(), List.of(),
                new PaperStructure.Statistics(2, 2, 0, 20, Map.of(), Map.of()),
                new PaperStructure.Quality(0.9, 0.9, 0, 0, List.of()), Instant.now());
    }
}
