package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.workbench.WorkbenchIntent;
import com.research.assistant.service.workbench.WorkbenchExecutionService;
import com.research.assistant.service.workbench.WorkbenchInvocation;
import com.research.assistant.service.workbench.WorkbenchPlan;
import com.research.assistant.service.workbench.WorkbenchRuleRouter;
import com.research.assistant.service.workbench.WorkbenchRunStatus;
import com.research.assistant.service.workbench.WorkbenchRunTrace;
import com.research.assistant.service.workbench.WorkbenchRunTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaperWorkbenchRunControllerContractTest {

    @Mock private WorkbenchRunTraceService service;
    @Mock private WorkbenchExecutionService executionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PaperWorkbenchRunController(service, executionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void plansBoundedRunWithStableResponseShape() throws Exception {
        WorkbenchRunTrace trace = trace();
        when(service.plan(any())).thenReturn(trace);

        mockMvc.perform(post("/api/workbench/runs/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.plan.workflow").value("SELECTION_QA"))
                .andExpect(jsonPath("$.data.plan.repairLimit").value(1));
    }

    @Test
    void rejectsEmptyPaperSetBeforePlanning() throws Exception {
        mockMvc.perform(post("/api/workbench/runs/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paperIds\":[],\"intent\":\"AUTO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void returnsHttp404ForUnknownRun() throws Exception {
        when(service.findTrace("missing")).thenReturn(null);

        mockMvc.perform(get("/api/workbench/runs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void listsRecentRunsForPaper() throws Exception {
        when(service.listRecentForPaper(7L, 3)).thenReturn(List.of(trace()));

        mockMvc.perform(get("/api/workbench/runs").param("paperId", "7").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runId").value("run-1"));
    }

    @Test
    void submitsPlannedRunForExecution() throws Exception {
        when(executionService.submit("run-1")).thenReturn(new WorkbenchExecutionService.Submission(
                "run-1", "task-1", WorkbenchRunStatus.QUEUED));

        mockMvc.perform(post("/api/workbench/runs/run-1/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    private WorkbenchRunTrace trace() {
        SelectionAnchor anchor = anchor();
        WorkbenchInvocation invocation = new WorkbenchInvocation(
                List.of(7L), "解释这个选区", WorkbenchIntent.ASK_SELECTION,
                WorkbenchPlan.Scope.SELECTION, anchor, 6, 4_000);
        WorkbenchPlan plan = new WorkbenchRuleRouter().route(invocation);
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 2, 0);
        return new WorkbenchRunTrace(
                "run-1", null, WorkbenchRunStatus.PLANNED, invocation, plan,
                List.of(new WorkbenchPlan.ArtifactVersion(7L, "a".repeat(64), "parser-v1", 0.9)),
                null, null, null, null, null, null, now, now,
                plan.steps().stream().map(step -> new WorkbenchRunTrace.StepTrace(
                        step.index(), step.name(), step.skill(), step.kind(),
                        com.research.assistant.service.workbench.WorkbenchStepStatus.PENDING,
                        0, 0, 0, 0, 0, 0, null, null, null, null, null, null)).toList());
    }

    private SelectionAnchor anchor() {
        return new SelectionAnchor(7L, 1,
                List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04)),
                "selected text", List.of("p1-b0001"), null, SelectionAnchorKind.TEXT,
                0.9, "a".repeat(64), "parser-v1");
    }

    private String validRequestJson() {
        return """
                {
                  "paperIds": [7],
                  "question": "解释这个选区",
                  "intent": "ASK_SELECTION",
                  "scope": "SELECTION",
                  "maxSteps": 6,
                  "tokenBudget": 4000,
                  "selectionAnchor": {
                    "paperId": 7,
                    "page": 1,
                    "boxes": [{"x":0.1,"y":0.2,"width":0.3,"height":0.04}],
                    "anchorText": "selected text",
                    "blockIds": ["p1-b0001"],
                    "kind": "TEXT",
                    "confidence": 0.9,
                    "documentHash": "%s",
                    "parserVersion": "parser-v1"
                  }
                }
                """.formatted("a".repeat(64));
    }
}
