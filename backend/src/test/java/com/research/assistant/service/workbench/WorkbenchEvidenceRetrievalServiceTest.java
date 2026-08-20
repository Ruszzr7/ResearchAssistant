package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkbenchEvidenceRetrievalServiceTest {

    private WorkbenchEvidenceRetrievalService service;

    @BeforeEach
    void setUp() {
        PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
        PaperLayoutEvidenceService projection = new PaperLayoutEvidenceService(
                policy, mock(SelectionAnchorResolver.class));
        service = new WorkbenchEvidenceRetrievalService(policy, projection);
    }

    @Test
    void samplesAcrossSectionsAndExcludesDecorativeOrReferenceBlocks() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("header", DocumentBlockRole.HEADER, 0, List.of(), "IEEE header"),
                block("abstract", DocumentBlockRole.ABSTRACT, 1, List.of(), "Low latency abstract"),
                block("h-intro", DocumentBlockRole.HEADING, 2, List.of("Introduction"), "I. Introduction"),
                block("intro", DocumentBlockRole.BODY, 3, List.of("Introduction"), "Motivation for low latency"),
                block("h-method", DocumentBlockRole.HEADING, 4, List.of("Method"), "II. Method"),
                block("method", DocumentBlockRole.BODY, 5, List.of("Method"), "Finite blocklength method"),
                block("results", DocumentBlockRole.BODY, 6, List.of("Results"), "Latency result improves"),
                block("reference", DocumentBlockRole.REFERENCE, 7, List.of("References"), "[1] unrelated"),
                block("footer", DocumentBlockRole.FOOTER, 8, List.of(), "page 1")));

        List<LayoutEvidence> result = service.retrievePaper(artifact, "latency method", 6, 8_000);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(LayoutEvidence::blockId)
                .contains("abstract", "intro", "method", "results")
                .doesNotContain("header", "reference", "footer");
        assertThat(result).extracting(LayoutEvidence::role)
                .allMatch(role -> role != DocumentBlockRole.HEADER
                        && role != DocumentBlockRole.FOOTER
                        && role != DocumentBlockRole.REFERENCE);
    }

    @Test
    void comparisonKeepsEvidenceFromEveryPaper() {
        PaperLayoutArtifact first = artifact(7L, List.of(
                block("p7-a", DocumentBlockRole.ABSTRACT, 1, List.of(), "Paper seven abstract"),
                block("p7-b", DocumentBlockRole.BODY, 2, List.of("Method"), "Paper seven method")));
        PaperLayoutArtifact second = artifact(8L, List.of(
                block("p8-a", DocumentBlockRole.ABSTRACT, 1, List.of(), "Paper eight abstract"),
                block("p8-b", DocumentBlockRole.BODY, 2, List.of("Method"), "Paper eight method")));

        List<LayoutEvidence> result = service.retrieveComparison(
                List.of(first, second), "compare method", 8, 8_000);

        assertThat(result).extracting(LayoutEvidence::paperId).contains(7L, 8L);
        assertThat(result.stream().filter(item -> item.paperId().equals(7L))).isNotEmpty();
        assertThat(result.stream().filter(item -> item.paperId().equals(8L))).isNotEmpty();
    }

    @Test
    void wholePaperSkipsRegionOnlyMathButKeepsStructuredMath() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                new DocumentBlock("region-formula", 1,
                        new NormalizedBoundingBox(0.1, 0.2, 0.4, 0.05),
                        DocumentBlockRole.FORMULA, 0, List.of("Method"), "broken glyphs",
                        null, null, 0.5, DocumentBlockContentMode.REGION),
                new DocumentBlock("structured-formula", 1,
                        new NormalizedBoundingBox(0.1, 0.3, 0.4, 0.05),
                        DocumentBlockRole.FORMULA, 1, List.of("Method"), "x = y + 1",
                        "x = y + 1", null, 0.9, DocumentBlockContentMode.STRUCTURED),
                block("body", DocumentBlockRole.BODY, 2, List.of("Method"), "Method context")));

        List<LayoutEvidence> result = service.retrievePaper(artifact, "method context", 6, 8_000);

        assertThat(result).extracting(LayoutEvidence::blockId)
                .contains("structured-formula", "body")
                .doesNotContain("region-formula");
    }

    @Test
    void paperWideFormulaEvaluationUsesNumberedFormulaStructureWithoutLexicalOverlap() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("abstract", DocumentBlockRole.ABSTRACT, 1, List.of(),
                        "This paper proposes a rate optimization framework."),
                block("signal-context", DocumentBlockRole.BODY, 10, List.of("II. System Model"),
                        "The transmitted composite signal is defined as follows."),
                regionFormula("signal-equation", 11, List.of("II. System Model"),
                        "x = sqrt(P) p s, (1)"),
                block("objective-context", DocumentBlockRole.BODY, 30,
                        List.of("IV. Problem Formulation"),
                        "The central optimization problem maximizes the ergodic sum rate."),
                regionFormula("objective-equation", 31, List.of("IV. Problem Formulation"),
                        "maximize R_sum subject to reliability constraints, (21)"),
                block("results", DocumentBlockRole.BODY, 50, List.of("V. Results"),
                        "Simulation results verify the proposed optimization.")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "你认为该文章最重要的一条公式是什么？", 12, 8_000);

        assertThat(result).isNotEmpty();
        assertThat(result).extracting(LayoutEvidence::blockId)
                .contains("signal-context", "objective-context",
                        "equation-entity:1:signal-equation",
                        "equation-entity:21:objective-equation");
        assertThat(result.stream().filter(item -> item.role() == DocumentBlockRole.FORMULA))
                .allMatch(item -> item.contentMode() == DocumentBlockContentMode.TEXT
                        && item.locator().precision()
                        == com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.FORMULA_REGION);
    }

    @Test
    void formulaOverviewRanksTheoremResultsAheadOfProofSteps() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("theorem", DocumentBlockRole.HEADING, 10, List.of("Analysis"),
                        "Theorem 2. The lower bound is stated below."),
                regionFormula("result31", 11, List.of("Analysis"),
                        "Rk = C(Gamma) - Q(beta). (31)"),
                block("proof", DocumentBlockRole.BODY, 12, List.of("Analysis"),
                        "Proof. Apply Lemmas 4, 5, and 6."),
                regionFormula("step34", 13, List.of("Analysis"),
                        "Rk approximately equals an expectation. (34)")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "你认为该文章最重要的一条公式是什么？", 8, 8_000);

        LayoutEvidence theoremResult = result.stream()
                .filter(item -> item.blockId().contains("result31")).findFirst().orElseThrow();
        LayoutEvidence proofStep = result.stream()
                .filter(item -> item.blockId().contains("step34")).findFirst().orElseThrow();
        assertThat(theoremResult.score()).isGreaterThan(proofStep.score());
        assertThat(theoremResult.sectionPath()).contains("Theorem 2 result");
        assertThat(proofStep.sectionPath()).contains("Theorem 2 proof step");
        assertThat(result).extracting(LayoutEvidence::blockId).contains("theorem", "proof");
    }

    @Test
    void formulaOverviewFallsBackToTrustworthyUnnumberedStructuredMath() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                new DocumentBlock("unnumbered", 1,
                        new NormalizedBoundingBox(0.1, 0.2, 0.4, 0.05),
                        DocumentBlockRole.FORMULA, 1, List.of("Method"), "x = y + 1",
                        "x = y + 1", null, 0.9, DocumentBlockContentMode.STRUCTURED),
                block("context", DocumentBlockRole.BODY, 2, List.of("Method"),
                        "The method uses an auxiliary identity.")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "该文章最重要的一条公式是什么？", 8, 8_000);

        assertThat(result).extracting(LayoutEvidence::blockId)
                .contains("unnumbered", "context");
    }

    @Test
    void locationQuestionAddsAdjacentFormulaRegionWithoutTreatingItAsFormulaText() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("intro", DocumentBlockRole.BODY, 1, List.of("System Model"),
                        "We first introduce the transmitted signal."),
                block("sinr-text", DocumentBlockRole.BODY, 2, List.of("System Model"),
                        "The common-stream SINR is defined for decoding at the receiver."),
                new DocumentBlock("sinr-formula-region", 1,
                        new NormalizedBoundingBox(0.12, 0.35, 0.72, 0.08),
                        DocumentBlockRole.FORMULA, 3, List.of("System Model"), "",
                        null, null, 0.6, DocumentBlockContentMode.REGION),
                block("optimization", DocumentBlockRole.BODY, 8, List.of("Optimization"),
                        "The objective minimizes total latency.")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "SINR 在哪里定义？", 5, 8_000);

        assertThat(result.get(0).blockId()).isEqualTo("sinr-text");
        assertThat(result).extracting(LayoutEvidence::blockId)
                .contains("sinr-text", "sinr-formula-region");
        LayoutEvidence region = result.stream()
                .filter(item -> item.blockId().equals("sinr-formula-region"))
                .findFirst().orElseThrow();
        assertThat(region.contentMode()).isEqualTo(DocumentBlockContentMode.REGION);
        assertThat(region.text()).contains("仅可按页面区域定位");
    }

    @Test
    void mixedChineseEnglishLocationQueryLinksOnlyARelevantEquationCluster() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                new DocumentBlock("unrelated-text", 1,
                        new NormalizedBoundingBox(0.1, 0.10, 0.38, 0.05),
                        DocumentBlockRole.BODY, 1, List.of("Introduction"),
                        "The introduction describes autonomous vehicles.",
                        null, null, 0.9),
                new DocumentBlock("unrelated-formula", 1,
                        new NormalizedBoundingBox(0.18, 0.17, 0.22, 0.04),
                        DocumentBlockRole.FORMULA, 2, List.of("Introduction"), "",
                        null, null, 0.8, DocumentBlockContentMode.REGION),
                new DocumentBlock("sinr-definition", 2,
                        new NormalizedBoundingBox(0.1, 0.42, 0.38, 0.06),
                        DocumentBlockRole.BODY, 10, List.of("System Model"),
                        "The signal-to-interference plus noise ratio (SINR) "
                                + "for the common stream is written as",
                        null, null, 0.9),
                new DocumentBlock("equation-label", 2,
                        new NormalizedBoundingBox(0.14, 0.49, 0.34, 0.04),
                        DocumentBlockRole.BODY, 11, List.of("System Model"),
                        "Gamma_c,k = fraction (4)", null, null, 0.86),
                new DocumentBlock("equation-fragment", 2,
                        new NormalizedBoundingBox(0.20, 0.50, 0.22, 0.05),
                        DocumentBlockRole.FORMULA, 12, List.of("System Model"),
                        "broken mathematical glyphs", null, null, 0.72,
                        DocumentBlockContentMode.REGION)));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "为我找出SINR公式在哪？", 5, 8_000);

        assertThat(result.get(0).blockId()).isEqualTo("sinr-definition");
        assertThat(result).extracting(LayoutEvidence::blockId)
                .contains("sinr-definition", "equation-region:equation-label")
                .doesNotContain("unrelated-text", "unrelated-formula");
        LayoutEvidence equation = result.stream()
                .filter(item -> item.blockId().equals("equation-region:equation-label"))
                .findFirst().orElseThrow();
        assertThat(equation.sectionPath()).contains("Equation (4)");
        assertThat(equation.bbox().x()).isEqualTo(0.14);
        assertThat(equation.bbox().right()).isGreaterThanOrEqualTo(0.48);
        assertThat(equation.bbox().bottom()).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    void colloquialChineseSignalNoiseQueryRetrievesAnEnglishSinrDefinition() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("sinr-definition", DocumentBlockRole.BODY, 1, List.of("System Model"),
                        "The signal-to-interference plus noise ratio (SINR) for the common stream is written as"),
                block("unrelated", DocumentBlockRole.BODY, 2, List.of("Experiments"),
                        "The experiment reports vehicle latency.")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "为我找出信噪比公式在哪？", 5, 8_000);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).blockId()).isEqualTo("sinr-definition");
        assertThat(result).extracting(LayoutEvidence::blockId).doesNotContain("unrelated");
    }

    @Test
    void currentTechnicalQuestionRanksMatchingBodyBeforeAbstractAndHeadings() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("abstract", DocumentBlockRole.ABSTRACT, 1, List.of(), "Autonomous driving overview"),
                block("heading", DocumentBlockRole.HEADING, 2, List.of("System Model"), "II. System Model"),
                block("sinr", DocumentBlockRole.BODY, 3, List.of("System Model"),
                        "The common-stream SINR gamma_c is defined at the receiver."),
                block("method", DocumentBlockRole.BODY, 4, List.of("Optimization"),
                        "The optimization algorithm alternates two subproblems.")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "SINR gamma_c 在哪里定义？", 3, 8_000);

        assertThat(result.get(0).blockId()).isEqualTo("sinr");
        assertThat(result).extracting(LayoutEvidence::blockId).contains("sinr");
    }

    @Test
    void previousTurnBlockIsOnlyAWeakHintWhenTheCurrentQuestionChangesTopic() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("previous", DocumentBlockRole.BODY, 1, List.of("System Model"),
                        "The SINR expression is introduced here."),
                block("current", DocumentBlockRole.BODY, 2, List.of("Experiments"),
                        "The ablation experiment reports latency improvements."),
                block("abstract", DocumentBlockRole.ABSTRACT, 3, List.of(), "General overview")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "ablation experiment latency", List.of("previous"), 2, 8_000);

        assertThat(result.get(0).blockId()).isEqualTo("current");
    }

    @Test
    void referentialFollowUpKeepsPreviousGroundedBlockAsContext() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("previous", DocumentBlockRole.BODY, 1, List.of("System Model"),
                        "The SINR expression is introduced here."),
                block("other", DocumentBlockRole.BODY, 2, List.of("Experiments"),
                        "The experiment reports latency improvements.")));

        List<LayoutEvidence> result = service.retrievePaper(
                artifact, "这个公式的变量分别是什么意思？", List.of("previous"), 2, 8_000);

        assertThat(result.get(0).blockId()).isEqualTo("previous");
    }

    private PaperLayoutArtifact artifact(Long paperId, List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(paperId, hash(paperId), "parser-v1", 0.9,
                Instant.parse("2026-07-16T00:00:00Z"), 2, blocks);
    }

    private DocumentBlock block(String id, DocumentBlockRole role, int order,
                                 List<String> section, String text) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(0.1, 0.1, 0.4, 0.05),
                role, order, section, text, null, null, 0.9);
    }

    private DocumentBlock regionFormula(String id, int order,
                                        List<String> section, String text) {
        return new DocumentBlock(id, 1,
                new NormalizedBoundingBox(0.12, 0.1 + order * 0.005, 0.52, 0.05),
                DocumentBlockRole.FORMULA, order, section, text,
                null, null, 0.86, DocumentBlockContentMode.REGION);
    }

    private String hash(Long paperId) {
        return Long.toHexString(paperId).repeat(64).substring(0, 64);
    }
}
