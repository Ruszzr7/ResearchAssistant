package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperFormulaRegionRecord;
import com.research.assistant.mapper.PaperFormulaRegionMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FormulaRegionServiceTest {

    private final PaperLayoutArtifactService artifactService = mock(PaperLayoutArtifactService.class);
    private final PaperMapper paperMapper = mock(PaperMapper.class);
    private final PaperFormulaRegionMapper regionMapper = mock(PaperFormulaRegionMapper.class);
    private final PaperPdfFileResolver fileResolver = mock(PaperPdfFileResolver.class);
    private final FormulaRegionImageService imageService = mock(FormulaRegionImageService.class);
    private final FormulaVisionRecognizer visionRecognizer = mock(FormulaVisionRecognizer.class);
    private final ConfirmedFormulaRegionService confirmedService = new ConfirmedFormulaRegionService(regionMapper);
    private final NormalizedBoundingBox bbox = new NormalizedBoundingBox(0.2, 0.3, 0.4, 0.1);
    private PaperLayoutArtifact artifact;
    private FormulaRegionService service;

    @BeforeEach
    void setUp() {
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        when(fileResolver.resolveRequired("paper.pdf")).thenReturn(new File("paper.pdf"));
        when(imageService.render(any(), any(Integer.class), any(), any()))
                .thenReturn(new FormulaRegionImage(new byte[]{1, 2, 3}, 100, 30));
        doAnswer(invocation -> {
            PaperFormulaRegionRecord record = invocation.getArgument(0);
            record.setId(11L);
            return 1;
        }).when(regionMapper).insert(any(PaperFormulaRegionRecord.class));
        service = new FormulaRegionService(
                artifactService, paperMapper, regionMapper, fileResolver, imageService,
                visionRecognizer, confirmedService);
    }

    @Test
    void reusesStructuredLayoutLatexWithoutCallingTheModel() {
        artifact = artifact(List.of(new DocumentBlock(
                "formula-1", 1, bbox, DocumentBlockRole.FORMULA, 4, List.of("Method"),
                "", "\\sum_{k=1}^{K} r_k", null, 0.94, DocumentBlockContentMode.STRUCTURED)));
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact);

        FormulaRegionRecognition result = service.recognize(7L, 1, bbox);

        assertThat(result.status()).isEqualTo(FormulaRegionStatus.CONFIRMED);
        assertThat(result.source()).isEqualTo(FormulaRegionSource.LAYOUT);
        assertThat(result.anchor()).isNotNull();
        assertThat(result.latex()).isEqualTo("\\sum_{k=1}^{K} r_k");
        verify(visionRecognizer, never()).recognize(any());
    }

    @Test
    void keepsMultimodalOutputAsCandidateUntilUserConfirmation() {
        artifact = artifact(List.of());
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact);
        when(visionRecognizer.recognize(any()))
                .thenReturn(new FormulaVisionRecognizer.FormulaCandidate("\\int_0^1 x\\,dx", 0.88));

        FormulaRegionRecognition result = service.recognize(7L, 1, bbox);

        assertThat(result.status()).isEqualTo(FormulaRegionStatus.CANDIDATE);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.anchor()).isNull();
    }

    @Test
    void providerFailureReturnsSafeRegionInsteadOfThrowing() {
        artifact = artifact(List.of());
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact);
        when(visionRecognizer.recognize(any())).thenThrow(new RuntimeException("provider rejected image"));

        FormulaRegionRecognition result = service.recognize(7L, 1, bbox);

        assertThat(result.status()).isEqualTo(FormulaRegionStatus.REGION);
        assertThat(result.anchor()).isNull();
        assertThat(result.message()).contains("手动填写");
    }

    @Test
    void persistenceFailureIsNotMisreportedAsModelFailure() {
        artifact = artifact(List.of());
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact);
        when(visionRecognizer.recognize(any()))
                .thenReturn(new FormulaVisionRecognizer.FormulaCandidate("x+y", 0.92));
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("database unavailable");
            }
            PaperFormulaRegionRecord record = invocation.getArgument(0);
            record.setId(11L);
            return 1;
        }).when(regionMapper).insert(any(PaperFormulaRegionRecord.class));

        assertThatThrownBy(() -> service.recognize(7L, 1, bbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void userConfirmationPromotesCandidateToVersionBoundFormulaAnchor() {
        artifact = artifact(List.of());
        PaperFormulaRegionRecord record = new PaperFormulaRegionRecord();
        record.setId(11L);
        record.setPaperId(7L);
        record.setDocumentHash(artifact.documentHash());
        record.setParserVersion(artifact.parserVersion());
        record.setPageNumber(1);
        record.setRegionKey("b".repeat(64));
        record.setBoxX(bbox.x());
        record.setBoxY(bbox.y());
        record.setBoxWidth(bbox.width());
        record.setBoxHeight(bbox.height());
        record.setStatus(FormulaRegionStatus.CANDIDATE.name());
        record.setSource(FormulaRegionSource.MULTIMODAL.name());
        when(regionMapper.selectById(11L)).thenReturn(record);
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact);

        FormulaRegionRecognition result = service.confirm(7L, 11L, "$$\\prod_{i=1}^{n} p_i$$");

        assertThat(result.status()).isEqualTo(FormulaRegionStatus.CONFIRMED);
        assertThat(result.source()).isEqualTo(FormulaRegionSource.USER);
        assertThat(result.latex()).isEqualTo("\\prod_{i=1}^{n} p_i");
        assertThat(result.anchor().kind().name()).isEqualTo("FORMULA");
        verify(regionMapper).updateById(record);
    }

    private PaperLayoutArtifact artifact(List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(
                7L, "a".repeat(64), "parser+semantic", 0.9,
                Instant.parse("2026-07-17T00:00:00Z"), 2, blocks);
    }
}
