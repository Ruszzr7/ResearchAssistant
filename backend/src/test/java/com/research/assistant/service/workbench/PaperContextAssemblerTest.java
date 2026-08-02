package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.memory.PaperConversationTurn;
import com.research.assistant.service.memory.PaperMemoryEvidenceRef;
import com.research.assistant.service.memory.PaperMemoryObservation;
import com.research.assistant.service.memory.PaperMemoryObservationService;
import com.research.assistant.service.memory.PaperStructure;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperContextAssemblerTest {

    private static final String HASH = "a".repeat(64);
    private static final String PARSER = "parser-v1";

    private PaperMemoryMapper memoryMapper;
    private PaperMemoryObservationService observationService;
    private WorkbenchRunTraceService traceService;
    private ObjectMapper objectMapper;
    private PaperContextAssembler assembler;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(PaperMemoryMapper.class);
        observationService = mock(PaperMemoryObservationService.class);
        traceService = mock(WorkbenchRunTraceService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        assembler = new PaperContextAssembler(
                memoryMapper, observationService, traceService, objectMapper);
    }

    @Test
    void assemblesBoundedServerOwnedContextWithExplicitSourcePriority() throws Exception {
        WorkbenchRunTrace trace = trace("run-context");
        when(traceService.readContextSnapshot(trace.runId(), PaperContextSnapshot.class)).thenReturn(null);
        when(observationService.recentConversation(
                7L, "session-91", HASH, PARSER, 8)).thenReturn(List.of(
                turn(1, "之前的问题", "服务端保存的回答"),
                turn(2, "更近的问题", "更近的服务端回答")));
        when(observationService.relevantObservations(
                eq(7L), eq(HASH), eq(PARSER), anyString(), eq("session-91"), eq(8)))
                .thenReturn(List.of(new PaperMemoryObservation(
                        11, "先前证据表明该方法降低估计误差", evidenceRefs(),
                        "old-run", "another-session", 2, Instant.now())));
        PaperMemoryRecord memory = new PaperMemoryRecord();
        memory.setProfileJson("""
                {
                  "schemaVersion":"paper-profile-v1",
                  "paperId":7,
                  "researchProblem":"有限块长可靠性问题",
                  "methodSummary":"联合优化方法",
                  "coreContributions":[{
                    "category":"CONTRIBUTION",
                    "statement":"提出联合优化框架",
                    "evidenceBlockIds":["p1-b0002"],
                    "confidence":0.9
                  }]
                }
                """);
        when(memoryMapper.selectVersion(7L, HASH, PARSER, PaperStructure.SCHEMA_VERSION))
                .thenReturn(memory);

        PaperContextSnapshot snapshot = assembler.assemble(trace, trace.invocation().selectionAnchor());

        assertThat(snapshot.budget().usedCharacters())
                .isLessThanOrEqualTo(PaperContextAssembler.MAX_CONTEXT_CHARACTERS);
        assertThat(snapshot.conversationTurns()).hasSize(2);
        assertThat(snapshot.relevantObservations()).singleElement()
                .satisfies(item -> assertThat(item.evidenceBlockIds()).containsExactly("p1-b0001"));
        assertThat(snapshot.profileContext()).contains("有限块长可靠性问题", "p1-b0002");
        assertThat(snapshot.sourcePriority()).startsWith(
                "CURRENT_QUESTION", "CURRENT_SELECTION_EVIDENCE", "CURRENT_RETRIEVED_EVIDENCE");
        assertThat(snapshot.modelQuestion())
                .contains("服务端历史", "服务端保存的回答", "当前 evidence", "当前问题：它有什么作用？");
        assertThat(snapshot.modelQuestion(1_200)).hasSizeLessThanOrEqualTo(1_200)
                .endsWith("当前问题：它有什么作用？");
        assertThat(snapshot.retrievalQuery())
                .contains("当前选区：selected text", "历史追问：")
                .doesNotContain("相关观察：", "论文画像：");
        assertThat(snapshot.preferredEvidenceBlockIds()).containsExactly("p1-b0001");
        assertThat(snapshot.selectionFingerprint()).hasSize(64);
        verify(traceService).saveContextSnapshot(
                eq(trace.runId()), eq(PaperContextSnapshot.SCHEMA_VERSION), any(PaperContextSnapshot.class));
    }

    @Test
    void reusesAFrozenSnapshotOnTaskRetry() {
        WorkbenchRunTrace trace = trace("run-retry");
        PaperContextSnapshot frozen = new PaperContextSnapshot(
                PaperContextSnapshot.SCHEMA_VERSION, 7L, HASH, PARSER, "session-91",
                "它有什么作用？", "selected text", List.of("p1-b0001"),
                PaperContextSnapshot.selectionFingerprint(trace.invocation().selectionAnchor()), "frozen profile",
                List.of(), List.of(), List.of("CURRENT_QUESTION"),
                new PaperContextSnapshot.Budget(8_000, 13, 0, 0, 14),
                false, Instant.parse("2026-07-19T00:00:00Z"));
        when(traceService.readContextSnapshot(trace.runId(), PaperContextSnapshot.class))
                .thenReturn(frozen);

        assertThat(assembler.assemble(trace, trace.invocation().selectionAnchor())).isSameAs(frozen);
    }

    @Test
    void rejectsFrozenSnapshotWhenCanonicalSelectionChanges() {
        WorkbenchRunTrace trace = trace("run-anchor-change");
        PaperContextSnapshot frozen = new PaperContextSnapshot(
                PaperContextSnapshot.SCHEMA_VERSION, 7L, HASH, PARSER, "session-91",
                "它有什么作用？", "old selection", List.of("p1-b0001"), "old-fingerprint", "",
                List.of(), List.of(), List.of("CURRENT_QUESTION"),
                new PaperContextSnapshot.Budget(8_000, 13, 0, 0, 0),
                false, Instant.parse("2026-07-19T00:00:00Z"));
        when(traceService.readContextSnapshot(trace.runId(), PaperContextSnapshot.class))
                .thenReturn(frozen);
        when(observationService.recentConversation(any(Long.class), anyString(), anyString(), anyString(), any(Integer.class)))
                .thenReturn(List.of());
        when(observationService.relevantObservations(
                any(Long.class), anyString(), anyString(), anyString(), anyString(), any(Integer.class)))
                .thenReturn(List.of());

        PaperContextSnapshot rebuilt = assembler.assemble(trace, trace.invocation().selectionAnchor());

        assertThat(rebuilt).isNotSameAs(frozen);
        assertThat(rebuilt.selectedText()).isEqualTo("selected text");
        assertThat(rebuilt.selectionFingerprint()).isEqualTo(
                PaperContextSnapshot.selectionFingerprint(trace.invocation().selectionAnchor()));
    }

    @Test
    void boundsLongQuestionsAndRetrievalExpansionWithoutDroppingSelection() {
        WorkbenchRunTrace trace = trace("run-bounds", "q".repeat(4_000));
        when(traceService.readContextSnapshot(trace.runId(), PaperContextSnapshot.class)).thenReturn(null);
        when(observationService.recentConversation(
                7L, "session-91", HASH, PARSER, 8)).thenReturn(List.of());
        when(observationService.relevantObservations(
                eq(7L), eq(HASH), eq(PARSER), anyString(), eq("session-91"), eq(8)))
                .thenReturn(List.of());

        PaperContextSnapshot snapshot = assembler.assemble(trace, trace.invocation().selectionAnchor());

        assertThat(snapshot.modelQuestion(1_200)).hasSizeLessThanOrEqualTo(1_200)
                .contains("当前问题：");
        assertThat(snapshot.retrievalQuery())
                .hasSizeLessThanOrEqualTo(PaperContextSnapshot.MAX_RETRIEVAL_QUERY_CHARACTERS)
                .contains("当前选区：selected text");
        assertThat(snapshot.retrievalQuery(64)).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void paperConversationUsesItsOwnHistoryAndProfileWithoutInventingASelection() {
        WorkbenchRunTrace trace = traceWithoutSelection("run-paper-chat", "paper-thread-2");
        when(traceService.readContextSnapshot(trace.runId(), PaperContextSnapshot.class)).thenReturn(null);
        when(observationService.recentConversation(
                7L, "paper-thread-2", HASH, PARSER, 8))
                .thenReturn(List.of(turn(3, "本对话上一问", "本对话上一答")));
        when(observationService.relevantObservations(
                eq(7L), eq(HASH), eq(PARSER), anyString(), eq("paper-thread-2"), eq(8)))
                .thenReturn(List.of());
        PaperMemoryRecord memory = new PaperMemoryRecord();
        memory.setProfileJson("""
                {
                  "schemaVersion":"paper-profile-v1",
                  "paperId":7,
                  "researchProblem":"论文画像中的研究问题",
                  "methodSummary":"论文画像中的方法"
                }
                """);
        when(memoryMapper.selectVersion(7L, HASH, PARSER, PaperStructure.SCHEMA_VERSION))
                .thenReturn(memory);

        PaperContextSnapshot snapshot = assembler.assemble(trace, null);

        assertThat(snapshot.conversationId()).isEqualTo("paper-thread-2");
        assertThat(snapshot.selectedText()).isEmpty();
        assertThat(snapshot.selectedBlockIds()).isEmpty();
        assertThat(snapshot.selectionFingerprint()).isEqualTo("none");
        assertThat(snapshot.modelQuestion())
                .contains("本对话上一问", "论文画像中的研究问题", "当前问题：继续解释");
        assertThat(snapshot.retrievalQuery()).doesNotContain("当前选区：");
    }

    private WorkbenchRunTrace trace(String runId) {
        return trace(runId, "它有什么作用？");
    }

    private WorkbenchRunTrace trace(String runId, String question) {
        SelectionAnchor anchor = new SelectionAnchor(
                7L, 1, List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04)),
                "selected text", List.of("p1-b0001"), null,
                SelectionAnchorKind.TEXT, 0.9, HASH, PARSER);
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), question, WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.SELECTION, anchor, 6, 10_000,
                "", "session-91");
        WorkbenchPlan plan = new WorkbenchRuleRouter().route(invocation);
        LocalDateTime now = LocalDateTime.now();
        return new WorkbenchRunTrace(
                runId, "task", WorkbenchRunStatus.RUNNING, invocation, plan,
                List.of(new WorkbenchPlan.ArtifactVersion(7L, HASH, PARSER, 0.9)),
                null, null, null, null, now, null, now, now, List.of());
    }

    private WorkbenchRunTrace traceWithoutSelection(String runId, String conversationId) {
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "继续解释", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.PAPER, null, 6, 10_000,
                "", conversationId);
        WorkbenchPlan plan = new WorkbenchRuleRouter().route(invocation);
        LocalDateTime now = LocalDateTime.now();
        return new WorkbenchRunTrace(
                runId, "task", WorkbenchRunStatus.RUNNING, invocation, plan,
                List.of(new WorkbenchPlan.ArtifactVersion(7L, HASH, PARSER, 0.9)),
                null, null, null, null, now, null, now, now, List.of());
    }

    private PaperConversationTurn turn(long id, String question, String answer) {
        return new PaperConversationTurn(
                id, "run-" + id, question, answer, List.of("p1-b0001"),
                List.of(), evidenceRefs(), Instant.now());
    }

    private List<PaperMemoryEvidenceRef> evidenceRefs() {
        return List.of(new PaperMemoryEvidenceRef(
                "lay_a", "p1-b0001", 1, List.of("Methods"), HASH, PARSER));
    }
}
