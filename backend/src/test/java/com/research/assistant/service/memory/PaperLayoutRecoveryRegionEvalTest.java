package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Stream;

/** Disposable corpus audit used to select staged real-model recovery cases. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.task.scheduling.enabled=false",
        "app.async.dispatch-enabled=false",
        "app.async.mark-orphaned-on-startup=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_PAPER_RECOVERY_REGION_EVAL", matches = "true")
class PaperLayoutRecoveryRegionEvalTest {

    @Autowired private PaperLayoutArtifactService artifactService;
    @Autowired private LayoutUncertainRegionDetector detector;
    @Autowired private PaperLayoutRecoveryStore recoveryStore;
    @Autowired private PaperSourceCatalogService sourceCatalogService;

    @Test
    void reportsLocalizedRegionsWithoutCallingAModel() {
        for (Long paperId : paperIds()) {
            PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
            List<LayoutUncertainRegion> regions = detector.detect(artifact);
            System.out.printf("RECOVERY_REGION_EVAL paper=%d pages=%d regions=%d details=%s%n",
                    paperId, artifact.pageCount(), regions.size(), regions.stream()
                            .map(region -> region.regionId() + ":" + region.issueType() + ":"
                                    + String.join(",", region.blockIds()))
                            .toList());
        }
    }

    @Test
    void exposesStoredCorrectionsAsClickableEvidenceWithoutCallingAModel() {
        for (Long paperId : paperIds()) {
            PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
            List<PaperLayoutRecovery> recoveries = recoveryStore.read(artifact);
            var catalog = sourceCatalogService.build(artifact);
            for (PaperLayoutRecovery recovery : recoveries) {
                var source = catalog.objects().values().stream()
                        .filter(object -> recovery.regionId().equals(
                                object.provenance().get("recoveryRegionId")))
                        .findFirst().orElseThrow();
                var locators = catalog.requireLocators(source.sourceObjectId());
                if (recovery.corrected()) {
                    org.assertj.core.api.Assertions.assertThat(source.rawContent())
                            .isEqualTo(recovery.correctedText());
                } else {
                    org.assertj.core.api.Assertions.assertThat(source.provenance().get("source"))
                            .isEqualTo("VISUAL_FALLBACK");
                    org.assertj.core.api.Assertions.assertThat(source.rawContent())
                            .contains("请查看原始页面区域");
                }
                org.assertj.core.api.Assertions.assertThat(locators)
                        .extracting(locator -> locator.pageNumber())
                        .containsExactlyElementsOf(recovery.pageAreas().stream()
                                .map(LayoutUncertainRegion.PageArea::page).toList());
                System.out.printf("RECOVERY_EVIDENCE_EVAL paper=%d region=%s source=%s pages=%s text=%s%n",
                        paperId, recovery.regionId(), source.sourceObjectId(),
                        locators.stream().map(locator -> locator.pageNumber()).toList(),
                        source.rawContent());
            }
        }
    }

    private List<Long> paperIds() {
        String configured = System.getenv("PAPER_UNDERSTANDING_EVAL_IDS");
        if (configured == null || configured.isBlank()) return List.of(184L, 185L, 192L, 197L);
        return Stream.of(configured.split(",")).map(String::trim).filter(value -> !value.isBlank())
                .map(Long::valueOf).toList();
    }
}
