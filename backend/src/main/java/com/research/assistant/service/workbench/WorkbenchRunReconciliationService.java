package com.research.assistant.service.workbench;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps persisted workbench run state aligned with its recoverable async task. */
@Service
public class WorkbenchRunReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchRunReconciliationService.class);

    private final WorkbenchRunTraceService traceService;
    private final WorkbenchExecutionService executionService;

    public WorkbenchRunReconciliationService(WorkbenchRunTraceService traceService,
                                             WorkbenchExecutionService executionService) {
        this.traceService = traceService;
        this.executionService = executionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcileActiveRuns();
    }

    @Scheduled(fixedDelayString = "${app.workbench.reconcile-delay-ms:30000}")
    public void reconcileActiveRuns() {
        for (WorkbenchRunTrace trace : traceService.listActive(200)) {
            try {
                executionService.reconcile(trace);
            } catch (RuntimeException error) {
                log.warn("event=workbench_reconcile_failed runId={} reason={}",
                        trace.runId(), error.getClass().getSimpleName());
            }
        }
    }
}
