package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.entity.PaperFormulaRegionRecord;
import com.research.assistant.mapper.PaperFormulaRegionMapper;
import com.research.assistant.service.pdf.layout.LocalEvidenceResult;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfirmedFormulaRegionServiceTest {

    @Test
    void confirmedRegionSurvivesCanonicalResolutionAndBecomesStructuredEvidence() {
        PaperFormulaRegionMapper mapper = mock(PaperFormulaRegionMapper.class);
        ConfirmedFormulaRegionService formulas = new ConfirmedFormulaRegionService(mapper);
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                7L, "a".repeat(64), "parser+semantic", 0.8, Instant.now(), 2, List.of());
        PaperFormulaRegionRecord record = record();
        when(mapper.selectConfirmedOnPage(7L, artifact.documentHash(), artifact.parserVersion(), 1))
                .thenReturn(List.of(record));
        PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
        SelectionAnchorResolver resolver = new SelectionAnchorResolver(policy, formulas);
        PaperLayoutEvidenceService evidenceService = new PaperLayoutEvidenceService(policy, resolver, formulas);

        SelectionAnchor anchor = resolver.resolve(
                artifact, 1, List.of(new NormalizedBoundingBox(0.19, 0.29, 0.42, 0.12)),
                "", SelectionAnchorKind.FORMULA);
        LocalEvidenceResult result = evidenceService.retrieve(artifact, anchor, "what is this", 8);

        assertThat(anchor.kind()).isEqualTo(SelectionAnchorKind.FORMULA);
        assertThat(anchor.anchorText()).isEqualTo("\\sum_{k=1}^{K} r_k");
        assertThat(result.regionFallback()).isFalse();
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceId()).startsWith("frm_");
            assertThat(evidence.structuredContent()).isEqualTo("\\sum_{k=1}^{K} r_k");
            assertThat(evidence.selected()).isTrue();
        });
    }

    private PaperFormulaRegionRecord record() {
        PaperFormulaRegionRecord record = new PaperFormulaRegionRecord();
        record.setId(3L);
        record.setPaperId(7L);
        record.setDocumentHash("a".repeat(64));
        record.setParserVersion("parser+semantic");
        record.setPageNumber(1);
        record.setRegionKey("b".repeat(64));
        record.setBoxX(0.2);
        record.setBoxY(0.3);
        record.setBoxWidth(0.4);
        record.setBoxHeight(0.1);
        record.setLatex("\\sum_{k=1}^{K} r_k");
        record.setConfidence(1d);
        record.setSource(FormulaRegionSource.USER.name());
        record.setStatus(FormulaRegionStatus.CONFIRMED.name());
        return record;
    }
}
