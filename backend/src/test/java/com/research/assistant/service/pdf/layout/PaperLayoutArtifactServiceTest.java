package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperLayoutArtifactRecord;
import com.research.assistant.mapper.PaperLayoutArtifactMapper;
import com.research.assistant.mapper.PaperMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperLayoutArtifactServiceTest {

    @TempDir
    Path tempDir;

    @Mock private PaperMapper paperMapper;
    @Mock private PaperLayoutArtifactMapper artifactMapper;
    @Mock private PaperLayoutParser parser;

    private ObjectMapper objectMapper;
    private PaperLayoutArtifactService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PaperLayoutArtifactService(
                paperMapper,
                artifactMapper,
                parser,
                new PaperLayoutSemanticEnricher(),
                new PaperMathContentEnricher(),
                new PaperPdfFileResolver(tempDir.toString()),
                objectMapper
        );
        lenient().when(parser.parserVersion()).thenReturn("pdfbox-layout-v1");
    }

    @Test
    void shouldReturnMatchingCachedArtifactWithoutParsingPdfAgain() throws Exception {
        Path pdf = Files.write(tempDir.resolve("cached.pdf"), new byte[] {1, 2, 3});
        Paper paper = paper(5L, pdf.getFileName().toString());
        String hash = PdfDocumentFingerprint.sha256(pdf.toFile());
        DocumentBlock block = block("cached evidence");
        PaperLayoutArtifactRecord cached = record(11L, 5L, hash, List.of(block));
        when(paperMapper.selectById(5L)).thenReturn(paper);
        when(artifactMapper.selectReady(5L, hash, "pdfbox-layout-v1+semantic-v6+inline-math-v1"))
                .thenReturn(cached);

        PaperLayoutArtifact artifact = service.ensureArtifact(5L, false);

        assertThat(artifact.documentHash()).isEqualTo(hash);
        assertThat(artifact.blocks()).extracting(DocumentBlock::text)
                .containsExactly("cached evidence");
        verify(parser, never()).parse(eq(5L), any(File.class), anyString());
    }

    @Test
    void shouldParseEnrichAndPersistWhenCacheDoesNotMatch() throws Exception {
        Path pdf = Files.write(tempDir.resolve("fresh.pdf"), new byte[] {4, 5, 6});
        Paper paper = paper(7L, pdf.getFileName().toString());
        paper.setTitle("A Test Paper");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        String hash = PdfDocumentFingerprint.sha256(pdf.toFile());
        Instant persistedInstant = Instant.parse("2026-07-15T17:20:35.532353Z");
        PaperLayoutArtifactRecord persisted = record(
                21L, 7L, hash, List.of(block("A Test Paper")));
        persisted.setGeneratedAt(LocalDateTime.ofInstant(
                persistedInstant, ZoneId.systemDefault()));
        when(artifactMapper.selectReady(7L, hash, "pdfbox-layout-v1+semantic-v6+inline-math-v1"))
                .thenReturn(null, persisted);
        PaperLayoutArtifact raw = new PaperLayoutArtifact(
                7L, hash, "pdfbox-layout-v1", 0.85, Instant.now(), 1,
                List.of(block("A Test Paper")));
        when(parser.parse(eq(7L), any(File.class), eq(hash))).thenReturn(raw);
        when(artifactMapper.insert(any(PaperLayoutArtifactRecord.class))).thenAnswer(invocation -> {
            PaperLayoutArtifactRecord inserted = invocation.getArgument(0);
            inserted.setId(21L);
            return 1;
        });

        PaperLayoutArtifact artifact = service.ensureArtifact(7L, false);

        assertThat(artifact.parserVersion()).isEqualTo("pdfbox-layout-v1+semantic-v6+inline-math-v1");
        assertThat(artifact.generatedAt()).isEqualTo(persistedInstant);
        ArgumentCaptor<PaperLayoutArtifactRecord> captor =
                ArgumentCaptor.forClass(PaperLayoutArtifactRecord.class);
        verify(artifactMapper).insert(captor.capture());
        assertThat(captor.getValue().getDocumentHash()).isEqualTo(hash);
        assertThat(captor.getValue().getParserVersion())
                .isEqualTo("pdfbox-layout-v1+semantic-v6+inline-math-v1");
        assertThat(captor.getValue().getBlocksJson()).contains("A Test Paper");
        assertThat(captor.getValue().getProvenanceJson()).contains("primaryParser");
        assertThat(captor.getValue().getStatus()).isEqualTo("READY");
    }

    @Test
    void latestArtifactIgnoresHistoricalArtifactWhenPdfHashOrParserVersionDiffers() throws Exception {
        Path pdf = Files.write(tempDir.resolve("current.pdf"), new byte[] {9, 8, 7});
        Paper paper = paper(8L, pdf.getFileName().toString());
        when(paperMapper.selectById(8L)).thenReturn(paper);

        PaperLayoutArtifact result = service.latestArtifact(8L);

        assertThat(result).isNull();
        verify(artifactMapper).selectReady(eq(8L), eq(PdfDocumentFingerprint.sha256(pdf.toFile())),
                eq("pdfbox-layout-v1+semantic-v6+inline-math-v1"));
        verify(artifactMapper, never()).selectLatestReady(8L);
    }

    @Test
    void resolverShouldRejectTraversalOutsideStorageRoot() {
        PaperPdfFileResolver resolver = new PaperPdfFileResolver(tempDir.toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolveRequired("..\\outside.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Paper paper(Long id, String pdfPath) {
        Paper paper = new Paper();
        paper.setId(id);
        paper.setTitle("Paper " + id);
        paper.setPdfPath(pdfPath);
        return paper;
    }

    private DocumentBlock block(String text) {
        return new DocumentBlock(
                "p1-b0001", 1, new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.02),
                DocumentBlockRole.BODY, 0, List.of(), text, null, null, 0.9);
    }

    private PaperLayoutArtifactRecord record(Long id,
                                             Long paperId,
                                             String hash,
                                             List<DocumentBlock> blocks) throws Exception {
        PaperLayoutArtifactRecord record = new PaperLayoutArtifactRecord();
        record.setId(id);
        record.setPaperId(paperId);
        record.setDocumentHash(hash);
        record.setParserVersion("pdfbox-layout-v1+semantic-v6+inline-math-v1");
        record.setStatus("READY");
        record.setLayoutConfidence(0.9);
        record.setPageCount(1);
        record.setBlocksJson(objectMapper.writeValueAsString(blocks));
        record.setGeneratedAt(LocalDateTime.now());
        return record;
    }
}
