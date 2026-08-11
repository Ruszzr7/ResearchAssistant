package com.research.assistant.service.workbench;

import com.research.assistant.service.memory.PaperConversationTurn;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchConversationClassifierTest {

    private final WorkbenchConversationClassifier classifier = new WorkbenchConversationClassifier(
            new WorkbenchRetrievalPlanner(), new WorkbenchCommandPlanner());

    @Test
    void classifiesReferencesAlternativesReasonsAndExpansionsAsFollowUps() {
        List<PaperConversationTurn> prior = List.of(turn(
                "你认为该文章最重要的一条公式是什么？",
                "我选公式 (22)。"));

        assertThat(List.of(
                "如果还要再选一条呢？",
                "为什么选择它？",
                "可以再详细说说吗？",
                "那么第二个候选是什么？"))
                .allSatisfy(question -> assertThat(classifier.classify(question, prior))
                        .isEqualTo(WorkbenchConversationRelation.FOLLOW_UP));
    }

    @Test
    void keepsUnrelatedAndExplicitNewTopicQuestionsIndependent() {
        List<PaperConversationTurn> prior = List.of(turn(
                "论文中最重要的公式是什么？", "公式 (22)。"));

        assertThat(classifier.classify("快速排序的复杂度是什么？", prior))
                .isEqualTo(WorkbenchConversationRelation.INDEPENDENT);
        assertThat(classifier.classify("换个话题，请解释熊彼特引理。", prior))
                .isEqualTo(WorkbenchConversationRelation.INDEPENDENT);
        assertThat(classifier.classify("这篇论文最关键的方法是什么？", prior))
                .isEqualTo(WorkbenchConversationRelation.INDEPENDENT);
    }

    @Test
    void distinguishesExplicitAndReferentialCommands() {
        List<PaperConversationTurn> prior = List.of(turn("公式 (5) 在哪？", "第 4 页。"));

        assertThat(classifier.classify("将信噪比公式所在位置高亮", prior))
                .isEqualTo(WorkbenchConversationRelation.INDEPENDENT);
        assertThat(classifier.classify("把它高亮", prior))
                .isEqualTo(WorkbenchConversationRelation.FOLLOW_UP);
    }

    private PaperConversationTurn turn(String question, String answer) {
        return new PaperConversationTurn(1, "run-1", question, answer,
                List.of("p1-b0001"), List.of(), List.of(), Instant.now());
    }
}
