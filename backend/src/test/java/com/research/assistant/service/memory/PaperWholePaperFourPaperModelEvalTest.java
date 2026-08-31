package com.research.assistant.service.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit, paid real-model regression for the four refactor papers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.task.scheduling.enabled=false",
        "app.async.dispatch-enabled=false",
        "app.async.mark-orphaned-on-startup=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_PAPER_UNDERSTANDING_REAL_EVAL", matches = "true")
class PaperWholePaperFourPaperModelEvalTest {

    @Autowired
    private PaperUnderstandingService understandingService;

    @Test
    void reparsesCurrentFourPapersWithOneCallEach() {
        for (Long paperId : paperIds()) {
            long started = System.nanoTime();
            PaperUnderstandingResult result = understandingService.understand(
                    paperId, true, stage -> System.out.printf(
                            "PAPER_EVAL paper=%d stage=%s%n", paperId, stage));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(result.status()).isEqualTo(PaperUnderstandingService.STATUS_READY);
            assertThat(result.profile()).isNotNull();
            assertThat(result.profile().coverage().complete()).isTrue();
            assertThat(result.summaries()).singleElement().satisfies(summary -> {
                assertThat(summary.ready()).isTrue();
                assertThat(summary.promptTokens()).isPositive();
                assertThat(summary.completionTokens()).isPositive();
            });
            assertThat(result.profile().coreContributions())
                    .allSatisfy(claim -> assertThat(claim.evidenceBlockIds()).isNotEmpty());
            assertThat(result.profile().keyFindings())
                    .allSatisfy(claim -> assertThat(claim.evidenceBlockIds()).isNotEmpty());

            System.out.printf(
                    "PAPER_EVAL paper=%d status=%s promptTokens=%d completionTokens=%d elapsedMs=%d contributions=%d findings=%d limitations=%d benchmarks=%d issues=%s%n",
                    paperId, result.status(), result.promptTokens(), result.completionTokens(), elapsedMs,
                    result.profile().coreContributions().size(), result.profile().keyFindings().size(),
                    result.profile().limitations().size(), result.profile().benchmarkResults().size(),
                    result.profile().qualityIssues());
        }
    }

    private static List<Long> paperIds() {
        String configured = System.getenv("PAPER_UNDERSTANDING_EVAL_IDS");
        if (configured == null || configured.isBlank()) return List.of(184L, 185L, 190L, 191L);
        return Stream.of(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
    }
}
