package com.research.assistant.service.workbench;

import com.research.assistant.dto.workbench.PdfWorkbenchEvalMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RA_LAYOUT_SAMPLE", matches = ".+")
class PdfWorkbenchRealEvalTest {

    @Autowired private PdfWorkbenchEvalService evalService;

    @Test
    void configuredWyPaperPassesTheCommittedRealEvaluationManifest() {
        PdfWorkbenchEvalMetrics metrics = evalService.evaluate();

        assertThat(metrics.cases())
                .filteredOn(result -> result.id().equals("wy-rsma-sum-rate-layout"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(PdfWorkbenchEvalMetrics.Status.PASSED);
                    assertThat(result.issues()).isEmpty();
                });
        assertThat(metrics.realExecuted()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.realPassed()).isGreaterThanOrEqualTo(1);
    }
}
