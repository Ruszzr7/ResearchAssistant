package com.research.assistant.service.agent.core;

import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.dto.agent.AgentTurnResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentMap;

/** Accepts a persisted Agent run before executing the potentially slow model loop. */
@Slf4j
@Service
public class AgentTurnSubmissionService {

    private final AgentLoopService loopService;
    private final TaskExecutor executor;
    private final ConcurrentMap<String, Future<?>> activeExecutions = new ConcurrentHashMap<>();

    public AgentTurnSubmissionService(AgentLoopService loopService,
                                      @Qualifier("agentTurnExecutor") TaskExecutor executor) {
        this.loopService = loopService;
        this.executor = executor;
    }

    public AgentTurnResult submit(AgentTurnInput input) {
        AgentLoopService.PreparedTurn prepared = loopService.prepare(input);
        if (prepared.duplicateRequest()) {
            return loopService.currentResult(prepared.run().getRunId());
        }
        AgentTurnResult accepted = loopService.currentResult(prepared.run().getRunId());
        try {
            Runnable task = () -> {
                String runId = prepared.run().getRunId();
                try {
                    executeInBackground(prepared);
                } finally {
                    activeExecutions.remove(runId);
                }
            };
            if (executor instanceof org.springframework.core.task.AsyncTaskExecutor asyncExecutor) {
                Future<?> future = asyncExecutor.submit(task);
                activeExecutions.put(prepared.run().getRunId(), future);
                if (future.isDone()) activeExecutions.remove(prepared.run().getRunId(), future);
            } else {
                executor.execute(task);
            }
        } catch (RuntimeException rejected) {
            loopService.rejectPrepared(prepared, rejected);
            throw rejected;
        }
        return accepted;
    }

    /** Best-effort interruption; the persisted run state remains the authority. */
    public void cancelExecution(String runId) {
        Future<?> future = activeExecutions.get(runId);
        if (future != null) future.cancel(true);
    }

    private void executeInBackground(AgentLoopService.PreparedTurn prepared) {
        try {
            loopService.executePrepared(prepared);
        } catch (RuntimeException failure) {
            // AgentLoopService has already persisted the FAILED state. The request thread must not own this work.
            log.warn("agent_run_background_failed runId={} type={} message={}",
                    prepared.run().getRunId(), failure.getClass().getSimpleName(), failure.getMessage(), failure);
        }
    }
}
