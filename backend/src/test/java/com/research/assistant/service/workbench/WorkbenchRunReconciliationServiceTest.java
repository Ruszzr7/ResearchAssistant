package com.research.assistant.service.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkbenchRunReconciliationServiceTest {

    @Test
    void reconcilesEveryBoundedActiveRun() {
        WorkbenchRunTraceService traceService = mock(WorkbenchRunTraceService.class);
        WorkbenchExecutionService executionService = mock(WorkbenchExecutionService.class);
        WorkbenchRunTrace first = mock(WorkbenchRunTrace.class);
        WorkbenchRunTrace second = mock(WorkbenchRunTrace.class);
        when(traceService.listActive(200)).thenReturn(List.of(first, second));
        WorkbenchRunReconciliationService service =
                new WorkbenchRunReconciliationService(traceService, executionService);

        service.reconcileActiveRuns();

        verify(executionService).reconcile(first);
        verify(executionService).reconcile(second);
    }
}
