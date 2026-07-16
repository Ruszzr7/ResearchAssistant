package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.workbench.PdfWorkbenchEvalMetrics;
import com.research.assistant.dto.workbench.PdfWorkbenchMetricsSnapshot;
import com.research.assistant.entity.PaperLayoutArtifactRecord;
import com.research.assistant.entity.PaperWorkbenchRunRecord;
import com.research.assistant.mapper.PaperLayoutArtifactMapper;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PdfWorkbenchMetricsServiceTest {

    @Mock private PaperLayoutArtifactMapper artifactMapper;
    @Mock private PaperWorkbenchRunMapper runMapper;
    @Mock private PdfWorkbenchEvalService evalService;
    private PdfWorkbenchMetricsService service;

    @BeforeEach
    void setUp() {
        service = new PdfWorkbenchMetricsService(
                artifactMapper, runMapper, evalService, new ObjectMapper().findAndRegisterModules());
        when(evalService.evaluate()).thenReturn(new PdfWorkbenchEvalMetrics(
                1, Instant.EPOCH, 7, 7, 4, 0, 0, 4, 1, List.of()));
    }

    @Test
    void aggregatesArtifactsAnchorsEvidenceGateAndWorkflowHealthWithoutContent() {
        when(artifactMapper.selectLatestReadyForMetrics()).thenReturn(List.of(
                artifact(1L, 0.62,
                        "{\"primaryParser\":\"pdfbox\",\"selectedParser\":\"mineru\","
                                + "\"fallbackEligible\":true,\"fallbackAttempted\":true,"
                                + "\"fallbackAccepted\":true,\"primaryQuality\":0.62,"
                                + "\"fallbackQuality\":0.84,\"qualityIssues\":[],"
                                + "\"fallbackFailureCode\":\"\"}"),
                artifact(2L, 0.90, null)));
        when(runMapper.selectForMetrics(any(LocalDateTime.class), eq(PdfWorkbenchMetricsService.MAX_RUNS)))
                .thenReturn(List.of(
                        completedSelection(),
                        completedComparison(),
                        failed("PAPER_COMPARISON", "EVIDENCE_GATE_REJECTED"),
                        failed("PAPER_ANALYSIS", "NO_EVIDENCE")));

        PdfWorkbenchMetricsSnapshot snapshot = service.snapshot(30);

        assertThat(snapshot.windowDays()).isEqualTo(30);
        assertThat(snapshot.layout().latestArtifacts()).isEqualTo(2);
        assertThat(snapshot.layout().averageQuality()).isEqualTo(0.76);
        assertThat(snapshot.layout().lowQualityArtifacts()).isEqualTo(1);
        assertThat(snapshot.layout().fallbackAccepted()).isEqualTo(1);
        assertThat(snapshot.layout().averageAcceptedQualityGain()).isEqualTo(0.22);
        assertThat(snapshot.layout().textBlocks()).isEqualTo(2);
        assertThat(snapshot.layout().structuredBlocks()).isEqualTo(2);
        assertThat(snapshot.layout().regionBlocks()).isEqualTo(2);
        assertThat(snapshot.anchors().selectionRuns()).isEqualTo(1);
        assertThat(snapshot.anchors().textAnchors()).isEqualTo(1);
        assertThat(snapshot.anchors().averageConfidence()).isEqualTo(0.91);
        assertThat(snapshot.evidence().claims()).isEqualTo(3);
        assertThat(snapshot.evidence().groundedClaims()).isEqualTo(3);
        assertThat(snapshot.evidence().claimCoverage()).isEqualTo(1.0);
        assertThat(snapshot.evidence().comparisonRuns()).isEqualTo(1);
        assertThat(snapshot.evidence().fullyCoveredComparisonRuns()).isEqualTo(1);
        assertThat(snapshot.evidence().evidenceGateRejectedFailures()).isEqualTo(1);
        assertThat(snapshot.evidence().noEvidenceFailures()).isEqualTo(1);
        assertThat(snapshot.runs().total()).isEqualTo(4);
        assertThat(snapshot.runs().completed()).isEqualTo(2);
        assertThat(snapshot.runs().failed()).isEqualTo(2);
        assertThat(snapshot.runs().completionRate()).isEqualTo(0.5);
        assertThat(snapshot.runs().repairRate()).isEqualTo(0.25);
        assertThat(snapshot.workflows()).hasSize(6);
        assertThat(snapshot.workflows()).extracting(PdfWorkbenchMetricsSnapshot.WorkflowSummary::workflow)
                .contains("PAPER_IMPROVEMENT");
        assertThat(snapshot.evaluation().allDeterministicPassed()).isTrue();
    }

    @Test
    void clampsMetricWindowAndReportsUnreadablePayloads() {
        when(artifactMapper.selectLatestReadyForMetrics()).thenReturn(List.of());
        PaperWorkbenchRunRecord run = failed("PAPER_ANALYSIS", "MODEL_FAILED");
        run.setRequestJson("not-json");
        when(runMapper.selectForMetrics(any(LocalDateTime.class), eq(PdfWorkbenchMetricsService.MAX_RUNS)))
                .thenReturn(List.of(run));

        PdfWorkbenchMetricsSnapshot snapshot = service.snapshot(999);

        assertThat(snapshot.windowDays()).isEqualTo(365);
        assertThat(snapshot.runs().unreadablePayloads()).isEqualTo(1);
    }

    private PaperLayoutArtifactRecord artifact(long paperId, double quality, String provenance) {
        PaperLayoutArtifactRecord record = new PaperLayoutArtifactRecord();
        record.setPaperId(paperId);
        record.setParserVersion("adaptive+semantic");
        record.setLayoutConfidence(quality);
        record.setProvenanceJson(provenance);
        record.setBlocksJson("""
                [
                  {"id":"t","page":1,"bbox":{"x":0.1,"y":0.1,"width":0.3,"height":0.1},"role":"BODY","readingOrder":0,"text":"body","confidence":0.9,"contentMode":"TEXT"},
                  {"id":"s","page":1,"bbox":{"x":0.1,"y":0.3,"width":0.3,"height":0.1},"role":"FORMULA","readingOrder":1,"text":"formula","latex":"x","confidence":0.9,"contentMode":"STRUCTURED"},
                  {"id":"r","page":1,"bbox":{"x":0.1,"y":0.5,"width":0.3,"height":0.1},"role":"TABLE","readingOrder":2,"text":"table","confidence":0.8,"contentMode":"REGION"}
                ]
                """);
        return record;
    }

    private PaperWorkbenchRunRecord completedSelection() {
        PaperWorkbenchRunRecord run = base("SELECTION_QA", "COMPLETED");
        run.setPaperIdsJson("[1]");
        run.setRepairCount(1);
        run.setRequestJson("""
                {"selectionAnchor":{"kind":"TEXT","confidence":0.91}}
                """);
        run.setResultJson("""
                {"regionFallback":false,"evidence":[{"evidenceId":"e1","paperId":1}],
                 "claims":[{"text":"claim","evidenceIds":["e1"]}]}
                """);
        return run;
    }

    private PaperWorkbenchRunRecord completedComparison() {
        PaperWorkbenchRunRecord run = base("PAPER_COMPARISON", "COMPLETED");
        run.setPaperIdsJson("[1,2]");
        run.setRequestJson("{}");
        run.setResultJson("""
                {"regionFallback":false,
                 "evidence":[{"evidenceId":"e1","paperId":1},{"evidenceId":"e2","paperId":2}],
                 "claims":[{"text":"first","evidenceIds":["e1"]},{"text":"second","evidenceIds":["e2"]}]}
                """);
        return run;
    }

    private PaperWorkbenchRunRecord failed(String workflow, String code) {
        PaperWorkbenchRunRecord run = base(workflow, "FAILED");
        run.setPaperIdsJson("[1]");
        run.setRequestJson("{}");
        run.setErrorCode(code);
        return run;
    }

    private PaperWorkbenchRunRecord base(String workflow, String status) {
        PaperWorkbenchRunRecord run = new PaperWorkbenchRunRecord();
        run.setWorkflow(workflow);
        run.setStatus(status);
        run.setLatencyMs(1_000L);
        run.setTotalTokens(200);
        run.setEvidenceCount(3);
        run.setRepairCount(0);
        return run;
    }
}
