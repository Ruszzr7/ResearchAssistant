package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchAnswerBlockTest {

    @Test
    void answerBlocksOverrideConflictingLegacyClaims() {
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                "SINR 位于第 4 页。", WorkbenchAnswerBlock.Basis.PAPER_FACT,
                List.of(new WorkbenchAnswerBlock.Citation("lay_current", "SINR")));

        WorkbenchModelOutput output = new WorkbenchModelOutput(
                block.text(),
                List.of(new WorkbenchEvidenceGate.GroundedClaim("old", List.of("lay_old"))),
                null, List.of(block));

        assertThat(output.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.text()).isEqualTo(block.text());
            assertThat(claim.evidenceIds()).containsExactly("lay_current");
        });
    }

    @Test
    void derivesGroundedClaimsFromPaperBlocksButNotGeneralKnowledge() {
        List<WorkbenchAnswerBlock> blocks = List.of(
                new WorkbenchAnswerBlock("本文在第三页定义 SINR。",
                        WorkbenchAnswerBlock.Basis.PAPER_FACT,
                        List.of(new WorkbenchAnswerBlock.Citation("lay_sinr", "SINR is defined"))),
                new WorkbenchAnswerBlock("一般而言，SINR 衡量信号相对干扰与噪声的强度。",
                        WorkbenchAnswerBlock.Basis.GENERAL_KNOWLEDGE, List.of()));
        WorkbenchModelOutput output = new WorkbenchModelOutput("", List.of(), null, blocks);

        assertThat(output.answer()).contains("第三页定义", "一般而言");
        assertThat(output.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.text()).contains("第三页定义");
            assertThat(claim.evidenceIds()).containsExactly("lay_sinr");
        });
        assertThat(output.hasOnlyNonPaperBlocks()).isFalse();
    }

    @Test
    void permitsAnExplicitlyGeneralAnswerWithoutFakePaperClaims() {
        List<WorkbenchAnswerBlock> blocks = List.of(
                new WorkbenchAnswerBlock("这是通用背景解释。",
                        WorkbenchAnswerBlock.Basis.GENERAL_KNOWLEDGE, List.of()));
        WorkbenchModelOutput output = new WorkbenchModelOutput("", List.of(), null, blocks);

        assertThat(output.claims()).isEmpty();
        assertThat(output.hasOnlyNonPaperBlocks()).isTrue();
        assertThat(output.toGateDraft(WorkbenchPlan.Workflow.SELECTION_QA).onlyNonPaperBlocks()).isTrue();
    }

    @Test
    void removesProviderCitationsFromExplicitNonPaperKnowledge() {
        WorkbenchModelOutput output = new WorkbenchModelOutput("回答", List.of(), null, List.of(
                new WorkbenchAnswerBlock("本文在第 4 页定义 SINR。",
                        WorkbenchAnswerBlock.Basis.PAPER_FACT,
                        List.of(new WorkbenchAnswerBlock.Citation("lay_sinr", "SINR"))),
                new WorkbenchAnswerBlock("一般而言，SINR 是信号质量指标。",
                        WorkbenchAnswerBlock.Basis.GENERAL_KNOWLEDGE,
                        List.of(new WorkbenchAnswerBlock.Citation("lay_sinr", "SINR"))),
                new WorkbenchAnswerBlock("当前证据未覆盖完整推导。",
                        WorkbenchAnswerBlock.Basis.EVIDENCE_LIMIT,
                        List.of(new WorkbenchAnswerBlock.Citation("lay_sinr", "SINR")))))
                .normalizeEvidenceQuotes(List.of());

        assertThat(output.answerBlocks().get(0).citations()).singleElement();
        assertThat(output.answerBlocks().get(1).citations()).isEmpty();
        assertThat(output.answerBlocks().get(2).citations()).isEmpty();
        assertThat(output.claims()).singleElement()
                .satisfies(claim -> assertThat(claim.evidenceIds()).containsExactly("lay_sinr"));
    }

    @Test
    void rebindsAnInventedEvidenceIdOnlyWhenItsExactQuoteHasOneCurrentMatch() {
        LayoutEvidence source = new LayoutEvidence(
                "lay_real", 7L, "p4-b0037", 4,
                new NormalizedBoundingBox(0.08, 0.52, 0.41, 0.04),
                DocumentBlockRole.BODY, 141, List.of("II. SYSTEM MODEL"),
                "The SINR for the common stream can be written as", 0.95, false,
                0.9, "a".repeat(64), "parser-v1");
        WorkbenchModelOutput output = new WorkbenchModelOutput("回答", List.of(), null, List.of(
                new WorkbenchAnswerBlock("公共流 SINR 位于系统模型。",
                        WorkbenchAnswerBlock.Basis.PAPER_FACT,
                        List.of(new WorkbenchAnswerBlock.Citation(
                                "lay_provider_rewrote_this", "SINR for the common stream")))))
                .normalizeEvidenceQuotes(List.of(source));

        assertThat(output.answerBlocks().get(0).citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.evidenceId()).isEqualTo("lay_real");
                    assertThat(citation.quote()).isEqualTo("SINR for the common stream");
                });
        assertThat(output.claims()).singleElement()
                .satisfies(claim -> assertThat(claim.evidenceIds()).containsExactly("lay_real"));
    }

}
