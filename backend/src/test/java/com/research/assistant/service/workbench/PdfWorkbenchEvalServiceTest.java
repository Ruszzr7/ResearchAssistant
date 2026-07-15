package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.workbench.PdfWorkbenchEvalMetrics;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.PaperLayoutParser;
import com.research.assistant.service.pdf.layout.PaperLayoutQualityAssessor;
import com.research.assistant.service.pdf.layout.PaperLayoutSemanticEnricher;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PdfWorkbenchEvalServiceTest {

    @Test
    void committedGoldenSetPassesWithoutCallingAModelOrRealPdfParser() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
        SelectionAnchorResolver resolver = new SelectionAnchorResolver(policy);
        PdfWorkbenchEvalService service = new PdfWorkbenchEvalService(
                objectMapper,
                new PaperLayoutSemanticEnricher(),
                new PaperLayoutQualityAssessor(),
                policy,
                resolver,
                new PaperLayoutEvidenceService(policy, resolver),
                mock(PaperLayoutParser.class),
                new MockEnvironment());

        PdfWorkbenchEvalMetrics metrics = service.evaluate();

        assertThat(metrics.schemaVersion()).isEqualTo(1);
        assertThat(metrics.deterministicCases()).isEqualTo(7);
        assertThat(metrics.cases())
                .filteredOn(result -> result.status() == PdfWorkbenchEvalMetrics.Status.FAILED)
                .as("golden case failures")
                .isEmpty();
        assertThat(metrics.deterministicPassed()).isEqualTo(7);
        assertThat(metrics.allDeterministicPassed()).isTrue();
        assertThat(metrics.realCases()).isEqualTo(4);
        assertThat(metrics.realExecuted()).isZero();
        assertThat(metrics.realSkipped()).isEqualTo(4);
        assertThat(metrics.passRate()).isEqualTo(1.0);
        assertThat(metrics.cases()).allSatisfy(result ->
                assertThat(result.status()).isIn(
                        PdfWorkbenchEvalMetrics.Status.PASSED,
                        PdfWorkbenchEvalMetrics.Status.SKIPPED));
    }
}
