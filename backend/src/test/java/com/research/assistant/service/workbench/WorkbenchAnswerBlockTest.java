package com.research.assistant.service.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchAnswerBlockTest {

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
}
