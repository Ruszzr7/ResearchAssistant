package com.research.assistant.service.workbench;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.pdf.formula.region.FormulaRegionImage;
import com.research.assistant.service.pdf.formula.region.FormulaRegionImageService;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.pdf.layout.SelectionContentType;
import com.research.assistant.service.pdf.layout.SelectionEvidenceUse;
import com.research.assistant.service.pdf.layout.SelectionMappingStatus;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkbenchSelectionVisualEvidenceServiceTest {

    private final PaperMapper paperMapper = mock(PaperMapper.class);
    private final PaperPdfFileResolver fileResolver = mock(PaperPdfFileResolver.class);
    private final FormulaRegionImageService imageService = mock(FormulaRegionImageService.class);
    private final WorkbenchSelectionVisualEvidenceService service =
            new WorkbenchSelectionVisualEvidenceService(paperMapper, fileResolver, imageService);

    @Test
    void rendersOneUnionCropForMathRichSelectionWithoutPersistingIt() {
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        File pdf = new File("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        when(fileResolver.resolveRequired("paper.pdf")).thenReturn(pdf);
        when(imageService.renderMasked(eq(pdf), eq(3), any(NormalizedBoundingBox.class),
                any(), eq("hash")))
                .thenReturn(new FormulaRegionImage(new byte[]{1, 2, 3}, 600, 240));

        WorkbenchSelectionVisualEvidence visual = service.create(mathAnchor());

        assertThat(visual.available()).isTrue();
        assertThat(visual.png()).containsExactly(1, 2, 3);
        assertThat(visual.page()).isEqualTo(3);
        assertThat(visual.bbox().x()).isCloseTo(0.514,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(visual.bbox().right()).isCloseTo(0.926,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(visual.selectionText()).contains("global power coefficient");
        verify(imageService).renderMasked(eq(pdf), eq(3), any(NormalizedBoundingBox.class),
                eq(mathAnchor().boxes()), eq("hash"));
    }

    @Test
    void returnsExplicitUnavailableEvidenceWhenLocalRenderingFails() {
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setPdfPath("paper.pdf");
        when(paperMapper.selectById(7L)).thenReturn(paper);
        when(fileResolver.resolveRequired("paper.pdf")).thenThrow(
                new IllegalArgumentException("missing"));

        WorkbenchSelectionVisualEvidence visual = service.create(mathAnchor());

        assertThat(visual.available()).isFalse();
        assertThat(visual.message()).contains("暂不可用", "回原页核对");
    }

    @Test
    void skipsPlainTextWithoutTouchingThePdf() {
        SelectionAnchor plain = new SelectionAnchor(
                7L, 3, List.of(new NormalizedBoundingBox(0.52, 0.4, 0.4, 0.1)),
                "plain text", List.of("body"), null, SelectionAnchorKind.TEXT, 0.9,
                "hash", "parser", SelectionMappingStatus.EXACT, SelectionContentType.PLAIN_TEXT,
                SelectionEvidenceUse.CLAIM_EVIDENCE, List.of(), null);

        assertThat(service.create(plain)).isNull();
        verify(paperMapper, never()).selectById(any());
    }

    @Test
    void skipsImageWhenSelectedFormulaAlreadyHasConfirmedLatex() {
        LayoutEvidence formula = new LayoutEvidence(
                "frm-1", 7L, "formula-region-1", 3,
                new NormalizedBoundingBox(0.52, 0.4, 0.4, 0.1),
                DocumentBlockRole.FORMULA, 10, List.of("Method"), "", 1, true,
                1, "hash", "parser", DocumentBlockContentMode.STRUCTURED, "x = y + z");

        assertThat(service.create(mathAnchor(), List.of(formula))).isNull();
        verify(paperMapper, never()).selectById(any());
    }

    private SelectionAnchor mathAnchor() {
        return new SelectionAnchor(
                7L, 3,
                List.of(
                        new NormalizedBoundingBox(0.52, 0.40, 0.35, 0.02),
                        new NormalizedBoundingBox(0.52, 0.44, 0.40, 0.12)),
                "where p_k ... global power coefficient", List.of("body"), null,
                SelectionAnchorKind.TEXT, 0.9, "hash", "parser",
                SelectionMappingStatus.EXACT, SelectionContentType.MATH_RICH_TEXT,
                SelectionEvidenceUse.CLAIM_EVIDENCE, List.of(), null);
    }
}
