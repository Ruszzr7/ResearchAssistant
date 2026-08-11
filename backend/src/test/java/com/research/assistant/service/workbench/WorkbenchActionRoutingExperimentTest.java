package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-only spike for the proposed action-first route. It intentionally does not
 * change the production router until the experiment has been accepted.
 */
class WorkbenchActionRoutingExperimentTest {

    private final WorkbenchCommandPlanner legacyPlanner = new WorkbenchCommandPlanner();
    private final ExperimentalActionRouter experimentalRouter = new ExperimentalActionRouter();

    @Test
    void productionParserNowRecognizesThePreviouslyFailingActionFirstPhrase() {
        assertThat(legacyPlanner.parse("高亮选定内容")).isPresent();
    }

    @Test
    void shouldRouteCommonSelectionCommandsBeforeQuestionAnswering() {
        SelectionFixture selection = exactTitleSelection();

        assertThat(List.of(
                "高亮",
                "高亮选定内容",
                "将这段文字高亮",
                "把当前选区标黄",
                "把它高亮",
                "高亮这段公式"
        )).allSatisfy(question -> {
            ExperimentalAction action = experimentalRouter.route(question, selection);
            assertThat(action.type()).isEqualTo(ActionType.HIGHLIGHT);
            assertThat(action.targetMode()).isEqualTo(TargetMode.CURRENT_SELECTION);
            assertThat(action.boxes()).containsExactlyElementsOf(selection.boxes());
        });
    }

    @Test
    void shouldExecuteCurrentSelectionWithoutModelOrEvidenceRetrieval() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger retrievalCalls = new AtomicInteger();
        AtomicInteger annotationWrites = new AtomicInteger();
        ExperimentalHarness harness = new ExperimentalHarness(
                experimentalRouter, modelCalls, retrievalCalls, annotationWrites);

        ExperimentalAction result = harness.execute("高亮选定内容", exactTitleSelection());

        assertThat(result.targetMode()).isEqualTo(TargetMode.CURRENT_SELECTION);
        assertThat(modelCalls).hasValue(0);
        assertThat(retrievalCalls).hasValue(0);
        assertThat(annotationWrites).hasValue(1);
    }

    @Test
    void shouldKeepAnnotationEligibilitySeparateFromAnswerEvidencePolicy() {
        NormalizedBoundingBox box = exactTitleSelection().boxes().get(0);
        DocumentBlock title = new DocumentBlock(
                "p1-title", 1, box, DocumentBlockRole.TITLE, 0, List.of(),
                "A paper title", null, null, 0.99);

        assertThat(new PaperLayoutEvidencePolicy().isAllowed(title)).isFalse();
        assertThat(experimentalRouter.route("高亮选定内容", exactTitleSelection()).ready()).isTrue();
    }

    @Test
    void shouldKeepExplicitSearchCommandsOutOfTheDirectSelectionPath() {
        ExperimentalAction action = experimentalRouter.route(
                "找出 SINR 公式并高亮", exactTitleSelection());

        assertThat(action.targetMode()).isEqualTo(TargetMode.EXPLICIT_QUERY);
        assertThat(action.query()).isEqualTo("SINR 公式");
        assertThat(action.ready()).isFalse();
    }

    private SelectionFixture exactTitleSelection() {
        return new SelectionFixture(
                188L,
                1,
                List.of(new NormalizedBoundingBox(0.08, 0.07, 0.83, 0.09)),
                "Autonomous Driving with RSMA-Enabled Finite Blocklength Transmissions",
                true);
    }

    private enum ActionType { HIGHLIGHT }

    private enum TargetMode { CURRENT_SELECTION, EXPLICIT_QUERY, PRIOR_REFERENT, NONE }

    private record SelectionFixture(long paperId,
                                    int page,
                                    List<NormalizedBoundingBox> boxes,
                                    String text,
                                    boolean exact) {
    }

    private record ExperimentalAction(ActionType type,
                                      TargetMode targetMode,
                                      String query,
                                      List<NormalizedBoundingBox> boxes,
                                      boolean ready) {
    }

    private static final class ExperimentalActionRouter {
        private static final List<String> HIGHLIGHT_VERBS = List.of("高亮", "标黄", "突出显示");
        private static final List<String> SEARCH_VERBS = List.of("找出", "找到", "定位", "搜索");

        ExperimentalAction route(String question, SelectionFixture selection) {
            String raw = question == null ? "" : question.trim();
            String value = normalize(raw);
            if (HIGHLIGHT_VERBS.stream().noneMatch(value::contains)) {
                return new ExperimentalAction(ActionType.HIGHLIGHT, TargetMode.NONE, "", List.of(), false);
            }
            String searchVerb = SEARCH_VERBS.stream().filter(value::contains).findFirst().orElse("");
            if (!searchVerb.isBlank()) {
                String target = raw.substring(value.indexOf(searchVerb) + searchVerb.length())
                        .replace("并高亮", "")
                        .replace("然后高亮", "")
                        .replace("高亮", "")
                        .trim();
                return new ExperimentalAction(
                        ActionType.HIGHLIGHT, TargetMode.EXPLICIT_QUERY, target, List.of(), false);
            }
            if (selection != null && selection.exact() && !selection.boxes().isEmpty()) {
                return new ExperimentalAction(
                        ActionType.HIGHLIGHT, TargetMode.CURRENT_SELECTION, "",
                        List.copyOf(selection.boxes()), true);
            }
            return new ExperimentalAction(
                    ActionType.HIGHLIGHT, TargetMode.PRIOR_REFERENT, "", List.of(), false);
        }

        private String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                    .replace("。", "")
                    .replace("！", "")
                    .replace("!", "");
        }
    }

    private record ExperimentalHarness(ExperimentalActionRouter router,
                                       AtomicInteger modelCalls,
                                       AtomicInteger retrievalCalls,
                                       AtomicInteger annotationWrites) {
        ExperimentalAction execute(String question, SelectionFixture selection) {
            ExperimentalAction action = router.route(question, selection);
            if (action.targetMode() == TargetMode.CURRENT_SELECTION && action.ready()) {
                annotationWrites.incrementAndGet();
                return action;
            }
            retrievalCalls.incrementAndGet();
            modelCalls.incrementAndGet();
            return action;
        }
    }
}
