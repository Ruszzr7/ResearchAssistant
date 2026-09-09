package com.research.assistant.service.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Synchronizes already-ready profiles into the legacy paper_analysis view without a model call. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.task.scheduling.enabled=false",
        "app.async.dispatch-enabled=false",
        "app.async.mark-orphaned-on-startup=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_PAPER_PROJECTION_REAL_EVAL", matches = "true")
class PaperFourPaperProjectionEvalTest {

    @Autowired
    private PaperUnderstandingService understandingService;

    @Autowired
    private PaperAnalysisProjectionService projectionService;

    @Test
    void projectsCurrentFourReadyProfilesWithoutRefreshingTheModel() {
        for (Long paperId : List.of(184L, 185L, 191L, 204L)) {
            PaperUnderstandingResult result = understandingService.understand(paperId, false, null);
            assertThat(result.status()).isEqualTo(PaperUnderstandingService.STATUS_READY);
            var analysis = projectionService.project(paperId, result);
            assertThat(analysis.getTokenUsed())
                    .isEqualTo(result.promptTokens() + result.completionTokens());
            System.out.printf("PROJECTION_EVAL paper=%d tokenUsed=%d%n",
                    paperId, analysis.getTokenUsed());
        }
    }
}
