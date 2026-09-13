package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.dto.agent.AgentRunEvent;
import com.research.assistant.dto.agent.AgentActionReceiptRequest;
import com.research.assistant.dto.agent.AgentActionReceiptResult;
import com.research.assistant.service.agent.core.AgentLoopService;
import com.research.assistant.service.agent.core.AgentRunEventService;
import com.research.assistant.service.agent.core.AgentTurnSubmissionService;
import com.research.assistant.service.agent.core.AgentRunCancellationService;
import com.research.assistant.service.agent.action.AgentActionReceiptService;
import com.research.assistant.service.agent.action.AgentActionTicketRenewalService;
import com.research.assistant.dto.agent.AgentPendingAction;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/agent/turns")
public class AgentTurnController {
    private final AgentLoopService loopService;
    private final AgentTurnSubmissionService submissionService;
    private final AgentRunEventService eventService;
    private final AgentActionReceiptService receiptService;
    private final AgentActionTicketRenewalService renewalService;
    private final AgentRunCancellationService cancellationService;

    public AgentTurnController(AgentLoopService loopService, AgentTurnSubmissionService submissionService,
                               AgentRunEventService eventService,
                               AgentActionReceiptService receiptService,
                               AgentActionTicketRenewalService renewalService,
                               AgentRunCancellationService cancellationService) {
        this.loopService = loopService;
        this.submissionService = submissionService;
        this.eventService = eventService;
        this.receiptService = receiptService;
        this.renewalService = renewalService;
        this.cancellationService = cancellationService;
    }

    @PostMapping
    public Result<AgentTurnResult> execute(@RequestBody AgentTurnInput input) {
        return Result.ok(submissionService.submit(input));
    }

    @GetMapping("/runs/{runId}")
    public Result<AgentTurnResult> status(@PathVariable String runId) {
        return Result.ok(loopService.currentResult(runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public Result<AgentTurnResult> cancel(@PathVariable String runId) {
        return Result.ok(cancellationService.cancel(runId));
    }

    @GetMapping("/runs/{runId}/events")
    public Result<List<AgentRunEvent>> events(@PathVariable String runId,
                                              @RequestParam(defaultValue = "0") long after) {
        return Result.ok(eventService.events(runId, after));
    }

    @GetMapping(value = "/runs/{runId}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String runId,
                             @RequestParam(defaultValue = "0") long after) {
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            for (AgentRunEvent event : eventService.events(runId, after)) {
                emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.type()).data(event.data()));
            }
            emitter.complete();
        } catch (IOException error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }

    @PostMapping("/actions/receipt")
    public Result<AgentActionReceiptResult> actionReceipt(@RequestBody AgentActionReceiptRequest request) {
        return Result.ok(receiptService.accept(request));
    }

    @PostMapping("/runs/{runId}/actions/{toolCallId}/ticket")
    public Result<AgentPendingAction> renewActionTicket(@PathVariable String runId,
                                                        @PathVariable String toolCallId) {
        return Result.ok(renewalService.renew(runId, toolCallId));
    }
}
