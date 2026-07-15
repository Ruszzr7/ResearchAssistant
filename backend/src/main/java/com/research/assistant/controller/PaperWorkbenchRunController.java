package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.workbench.WorkbenchRunPlanRequest;
import com.research.assistant.service.workbench.WorkbenchExecutionService;
import com.research.assistant.service.workbench.WorkbenchRunTrace;
import com.research.assistant.service.workbench.WorkbenchRunTraceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workbench/runs")
public class PaperWorkbenchRunController {

    private final WorkbenchRunTraceService runTraceService;
    private final WorkbenchExecutionService executionService;

    public PaperWorkbenchRunController(WorkbenchRunTraceService runTraceService,
                                       WorkbenchExecutionService executionService) {
        this.runTraceService = runTraceService;
        this.executionService = executionService;
    }

    @PostMapping("/plan")
    public Result<WorkbenchRunTrace> plan(@Valid @RequestBody WorkbenchRunPlanRequest request) {
        return Result.ok(runTraceService.plan(request.toInvocation()));
    }

    @PostMapping("/{runId}/execute")
    public Result<WorkbenchExecutionService.Submission> execute(@PathVariable String runId) {
        return Result.ok(executionService.submit(runId));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<Result<WorkbenchRunTrace>> get(@PathVariable String runId) {
        WorkbenchRunTrace trace = runTraceService.findTrace(runId);
        if (trace == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(HttpStatus.NOT_FOUND.value(), "工作台运行不存在"));
        }
        return ResponseEntity.ok(Result.ok(trace));
    }
}
