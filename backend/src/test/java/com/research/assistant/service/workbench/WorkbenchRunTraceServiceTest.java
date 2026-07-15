package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import com.research.assistant.mapper.PaperWorkbenchStepMapper;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkbenchRunTraceServiceTest {

    @Autowired private PaperWorkbenchRunMapper runMapper;
    @Autowired private PaperWorkbenchStepMapper stepMapper;
    @Autowired private ObjectMapper objectMapper;

    private PaperLayoutArtifactService artifactService;
    private WorkbenchRunTraceService service;

    @BeforeEach
    void setUp() {
        artifactService = mock(PaperLayoutArtifactService.class);
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact("a".repeat(64)));
        service = new WorkbenchRunTraceService(
                runMapper, stepMapper, new WorkbenchRuleRouter(), artifactService, objectMapper);
    }

    @Test
    void persistsPlanStepsVersionsAndCompletedMetrics() {
        WorkbenchRunTrace planned = service.plan(selectionInvocation(anchor("a".repeat(64))));

        assertThat(planned.status()).isEqualTo(WorkbenchRunStatus.PLANNED);
        assertThat(planned.plan().workflow()).isEqualTo(WorkbenchPlan.Workflow.SELECTION_QA);
        assertThat(planned.artifactVersions()).singleElement()
                .satisfies(version -> assertThat(version.documentHash()).isEqualTo("a".repeat(64)));
        assertThat(planned.steps()).hasSize(4)
                .allMatch(step -> step.status() == WorkbenchStepStatus.PENDING);

        service.startRun(planned.runId(), "task-1");
        for (int index = 0; index < 4; index++) {
            service.startStep(planned.runId(), index, Map.of("paperCount", 1));
            service.completeStep(planned.runId(), index, Map.of("ok", true),
                    index == 1 ? 2 : 0, index == 2 ? 10 : 0, index == 2 ? 5 : 0, 12);
        }
        service.completeRun(planned.runId(), Map.of("answer", "grounded"), 2);

        WorkbenchRunTrace completed = service.requireTrace(planned.runId());
        assertThat(completed.status()).isEqualTo(WorkbenchRunStatus.COMPLETED);
        assertThat(completed.taskId()).isEqualTo("task-1");
        assertThat(completed.metrics().evidenceCount()).isEqualTo(2);
        assertThat(completed.metrics().totalTokens()).isEqualTo(15);
        assertThat(completed.steps()).allMatch(step -> step.status() == WorkbenchStepStatus.COMPLETED);
        assertThat(completed.result()).isEqualTo(Map.of("answer", "grounded"));
    }

    @Test
    void reopensGenerationAndGateExactlyOnceForRepair() {
        WorkbenchRunTrace planned = service.plan(selectionInvocation(anchor("a".repeat(64))));
        service.startRun(planned.runId(), null);
        for (int index = 0; index < 3; index++) {
            service.startStep(planned.runId(), index, null);
            service.completeStep(planned.runId(), index, null, 0, 0, 0, 1);
        }
        service.startStep(planned.runId(), 3, null);
        service.failStep(planned.runId(), 3, "EVIDENCE_GATE_REJECTED", "证据不足", 1);

        service.prepareSingleRepair(planned.runId(), 2, 3);
        WorkbenchRunTrace repair = service.requireTrace(planned.runId());
        assertThat(repair.metrics().repairCount()).isEqualTo(1);
        assertThat(repair.steps().get(2).status()).isEqualTo(WorkbenchStepStatus.PENDING);
        assertThat(repair.steps().get(3).status()).isEqualTo(WorkbenchStepStatus.PENDING);
        assertThat(repair.steps().get(2).retryCount()).isEqualTo(1);
        assertThat(repair.steps().get(3).retryCount()).isEqualTo(1);

        service.startStep(planned.runId(), 2, null);
        service.completeStep(planned.runId(), 2, null, 0, 0, 0, 1);
        service.startStep(planned.runId(), 3, null);
        service.failStep(planned.runId(), 3, "EVIDENCE_GATE_REJECTED", "仍然不足", 1);

        assertThatThrownBy(() -> service.prepareSingleRepair(planned.runId(), 2, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void rejectsAnchorFromAnObsoleteArtifactVersion() {
        assertThatThrownBy(() -> service.plan(selectionInvocation(anchor("b".repeat(64)))))
                .isInstanceOf(StaleLayoutArtifactException.class);
    }

    @Test
    void listsNewestRunsForPaperWithBoundedLimit() {
        WorkbenchRunTrace first = service.plan(selectionInvocation(anchor("a".repeat(64))));
        WorkbenchRunTrace second = service.plan(selectionInvocation(anchor("a".repeat(64))));

        List<WorkbenchRunTrace> recent = service.listRecentForPaper(7L, 1);

        assertThat(recent).extracting(WorkbenchRunTrace::runId).containsExactly(second.runId());
        assertThat(recent).extracting(WorkbenchRunTrace::runId).doesNotContain(first.runId());
    }

    @Test
    void taskRetryReplaysUncheckedModelButPreservesApprovedCheckpoint() {
        WorkbenchRunTrace planned = service.plan(selectionInvocation(anchor("a".repeat(64))));
        service.prepareExecutionAttempt(planned.runId(), "task-recovery");
        for (int index = 0; index < 3; index++) {
            service.startStep(planned.runId(), index, null);
            service.completeStep(planned.runId(), index, null, 0,
                    index == 2 ? 10 : 0, index == 2 ? 5 : 0, 1);
        }

        WorkbenchRunTrace beforeGate = service.prepareExecutionAttempt(planned.runId(), "task-recovery");
        assertThat(beforeGate.steps().get(0).status()).isEqualTo(WorkbenchStepStatus.COMPLETED);
        assertThat(beforeGate.steps().get(2).status()).isEqualTo(WorkbenchStepStatus.PENDING);
        assertThat(beforeGate.steps().get(2).retryCount()).isEqualTo(1);
        assertThat(beforeGate.steps().get(2).totalTokens()).isEqualTo(15);

        service.startStep(planned.runId(), 2, null);
        service.completeStep(planned.runId(), 2, null, 0, 3, 2, 1);
        service.startStep(planned.runId(), 3, null);
        service.completeStep(planned.runId(), 3, null, 1, 0, 0, 1);
        service.checkpointResult(planned.runId(), Map.of("answer", "approved"));

        WorkbenchRunTrace approved = service.prepareExecutionAttempt(planned.runId(), "task-recovery");
        assertThat(approved.steps().get(2).status()).isEqualTo(WorkbenchStepStatus.COMPLETED);
        assertThat(approved.steps().get(3).status()).isEqualTo(WorkbenchStepStatus.COMPLETED);
        assertThat(approved.result()).isEqualTo(Map.of("answer", "approved"));
    }

    @Test
    void taskRetryReplaysGateCompletedWithoutApprovedCheckpoint() {
        WorkbenchRunTrace planned = service.plan(selectionInvocation(anchor("a".repeat(64))));
        service.prepareExecutionAttempt(planned.runId(), "task-crash-window");
        for (int index = 0; index < 4; index++) {
            service.startStep(planned.runId(), index, null);
            service.completeStep(planned.runId(), index, null, index == 3 ? 1 : 0,
                    index == 2 ? 10 : 0, index == 2 ? 5 : 0, 1);
        }

        WorkbenchRunTrace recovered = service.prepareExecutionAttempt(planned.runId(), "task-crash-window");

        assertThat(recovered.steps().get(2).status()).isEqualTo(WorkbenchStepStatus.PENDING);
        assertThat(recovered.steps().get(3).status()).isEqualTo(WorkbenchStepStatus.PENDING);
        assertThat(recovered.steps().get(2).retryCount()).isEqualTo(1);
        assertThat(recovered.steps().get(3).retryCount()).isEqualTo(1);
    }

    private WorkbenchInvocation selectionInvocation(SelectionAnchor anchor) {
        return new WorkbenchInvocation(
                List.of(7L), "解释这个选区", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.SELECTION, anchor, 6, 4_000);
    }

    private SelectionAnchor anchor(String hash) {
        return new SelectionAnchor(7L, 1,
                List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04)),
                "selected text", List.of("p1-b0001"), null, SelectionAnchorKind.TEXT,
                0.9, hash, "parser-v1");
    }

    private PaperLayoutArtifact artifact(String hash) {
        return new PaperLayoutArtifact(7L, hash, "parser-v1", 0.91,
                Instant.parse("2026-07-16T00:00:00Z"), 10, List.of());
    }
}
