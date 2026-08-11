package com.research.assistant.service.workbench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchRetrievalPlannerTest {

    private final WorkbenchRetrievalPlanner planner = new WorkbenchRetrievalPlanner();

    @Test
    void classifiesLocationAndKeepsTechnicalAcronym() {
        WorkbenchRetrievalPlan plan = planner.plan("为我找出 SINR 公式在哪？");

        assertThat(plan.queryType()).isEqualTo(WorkbenchRetrievalPlan.QueryType.LOCATION);
        assertThat(plan.formulaOrLocation()).isTrue();
        assertThat(plan.terms()).contains("sinr");
    }

    @Test
    void bridgesCommonChineseResearchTermsWithoutASecondModelCall() {
        WorkbenchRetrievalPlan plan = planner.plan("公共流功率系数如何分配？");

        assertThat(plan.terms()).contains("common stream", "power", "coefficient", "allocation");
    }

    @Test
    void mapsColloquialChineseSignalNoiseQuestionToBothSinrAndSnr() {
        WorkbenchRetrievalPlan plan = planner.plan("为我找出信噪比公式在哪？");

        assertThat(plan.terms()).contains(
                "sinr", "signal-to-interference plus noise ratio",
                "snr", "signal-to-noise ratio");
        assertThat(plan.terms()).doesNotContain("为我找出信噪比公式在哪");
    }

    @Test
    void marksReferentialFollowUp() {
        WorkbenchRetrievalPlan plan = planner.plan("这个公式里的变量分别是什么意思？");

        assertThat(plan.referentialFollowUp()).isTrue();
        assertThat(plan.queryType()).isEqualTo(WorkbenchRetrievalPlan.QueryType.DEFINITION);
    }

    @Test
    void treatsPaperWideImportanceQuestionAsFormulaSynthesisInsteadOfLiteralSearch() {
        WorkbenchRetrievalPlan plan = planner.plan("你认为该文章最重要的一条公式是什么？");

        assertThat(plan.queryType()).isEqualTo(WorkbenchRetrievalPlan.QueryType.SUMMARY);
        assertThat(plan.broad()).isTrue();
        assertThat(plan.formulaOverview()).isTrue();
        assertThat(plan.terms()).doesNotContain("你认为该最重要的一条是么", "最重要的一条");
    }

    @Test
    void treatsOtherPaperWideEvaluationsAsBroadSynthesis() {
        assertThat(planner.plan("本文最关键的方法是什么？").broad()).isTrue();
        assertThat(planner.plan("哪项实验结果最有说服力？").broad()).isTrue();
        assertThat(planner.plan("快速排序的复杂度是什么？").broad()).isFalse();
    }

    @Test
    void detectsTechnicalContinuityWithoutTreatingAnUnrelatedQuestionAsAFollowUp() {
        assertThat(planner.semanticallyRelated(
                "为我找出信噪比公式在哪？",
                "前一轮解释了公共流与私有流的 SINR 公式。")).isTrue();
        assertThat(planner.semanticallyRelated(
                "快速排序的复杂度是什么？",
                "前一轮解释了公共流与私有流的 SINR 公式。")).isFalse();
    }
}
