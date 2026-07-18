package com.research.assistant.service.memory;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperConversationTurnMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperMemoryObservationMapper;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.workbench.WorkbenchEvidenceGate;
import com.research.assistant.service.workbench.WorkbenchIntent;
import com.research.assistant.service.workbench.WorkbenchInvocation;
import com.research.assistant.service.workbench.WorkbenchPlan;
import com.research.assistant.service.workbench.WorkbenchRuleRouter;
import com.research.assistant.service.workbench.WorkbenchRunStatus;
import com.research.assistant.service.workbench.WorkbenchRunTrace;
import com.research.assistant.service.workbench.WorkbenchWorkflowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaperMemoryObservationServiceTest {

    private static final String HASH = "a".repeat(64);
    private static final String PARSER = "parser-v1";

    @Autowired private PaperMemoryObservationService service;
    @Autowired private PaperConversationTurnMapper turnMapper;
    @Autowired private PaperMemoryObservationMapper observationMapper;
    @Autowired private PaperMapper paperMapper;

    private long paperId;

    @BeforeEach
    void setUp() {
        Paper paper = new Paper();
        paper.setTitle("Context Memory Paper");
        paperMapper.insert(paper);
        paperId = paper.getId();
    }

    @Test
    void storesTurnsAndDeduplicatesGroundedClaimsAcrossRuns() {
        WorkbenchWorkflowResult first = result("run-1", "该方法降低估计误差。", "降低估计误差", "lay_a");
        service.remember(trace("run-1", "问题一"), first);
        service.remember(trace("run-1", "问题一"), first);

        assertThat(turnMapper.selectBySourceRunId("run-1")).isNotNull();
        var afterFirst = observationMapper.selectRecentVersion(paperId, HASH, PARSER, 10);
        assertThat(afterFirst).singleElement()
                .satisfies(item -> assertThat(item.getConfirmationCount()).isEqualTo(1));

        service.remember(trace("run-2", "问题二"),
                result("run-2", "再次确认该方法降低估计误差。", "降低估计误差", "lay_b"));

        var observations = observationMapper.selectRecentVersion(paperId, HASH, PARSER, 10);
        assertThat(observations).singleElement().satisfies(item -> {
            assertThat(item.getConfirmationCount()).isEqualTo(2);
            assertThat(item.getSourceRunId()).isEqualTo("run-2");
            assertThat(item.getEvidenceRefsJson()).contains("lay_a", "lay_b", "p1-b0001");
        });
        assertThat(service.recentConversation(paperId, "session-91", HASH, PARSER, 8))
                .extracting(PaperConversationTurn::sourceRunId)
                .containsExactly("run-1", "run-2");
        assertThat(service.relevantObservations(
                paperId, HASH, PARSER, "估计误差", "another-session", 5))
                .singleElement()
                .satisfies(item -> assertThat(item.confirmationCount()).isEqualTo(2));
    }

    @Test
    void rejectsClaimsWhoseCitationsAreOutsideTheCurrentEvidenceSet() {
        WorkbenchWorkflowResult invalid = result(
                "run-invalid", "无依据回答", "无依据 claim", "lay_unknown");

        service.remember(trace("run-invalid", "问题"), invalid);

        assertThat(turnMapper.selectBySourceRunId("run-invalid")).isNull();
        assertThat(observationMapper.selectRecentVersion(paperId, HASH, PARSER, 10)).isEmpty();
    }

    private WorkbenchRunTrace trace(String runId, String question) {
        SelectionAnchor anchor = new SelectionAnchor(
                paperId, 1, List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04)),
                "selected", List.of("p1-b0001"), null, SelectionAnchorKind.TEXT,
                0.9, HASH, PARSER);
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(paperId), question, WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.SELECTION, anchor, 6, 10_000,
                "", "session-91", "");
        WorkbenchPlan plan = new WorkbenchRuleRouter().route(invocation);
        LocalDateTime now = LocalDateTime.now();
        return new WorkbenchRunTrace(
                runId, "task", WorkbenchRunStatus.COMPLETED, invocation, plan,
                List.of(new WorkbenchPlan.ArtifactVersion(paperId, HASH, PARSER, 0.9)),
                null, null, null, null, now, now, now, now, List.of());
    }

    private WorkbenchWorkflowResult result(String runId,
                                            String answer,
                                            String claimText,
                                            String claimEvidenceId) {
        LayoutEvidence evidence = new LayoutEvidence(
                claimEvidenceId.equals("lay_unknown") ? "lay_actual" : claimEvidenceId,
                paperId, "p1-b0001", 1,
                new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04), DocumentBlockRole.BODY,
                1, List.of("Methods"), "evidence text", 0.9, true, 0.95, HASH, PARSER);
        return new WorkbenchWorkflowResult(
                runId, WorkbenchPlan.Workflow.SELECTION_QA, WorkbenchPlan.Scope.SELECTION,
                List.of(paperId), answer,
                List.of(new WorkbenchEvidenceGate.GroundedClaim(claimText, List.of(claimEvidenceId))),
                List.of(evidence), null, false, 0);
    }
}
