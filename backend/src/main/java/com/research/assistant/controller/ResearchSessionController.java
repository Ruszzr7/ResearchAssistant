package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.research.ResearchMessageAppendRequest;
import com.research.assistant.dto.research.ResearchMessageView;
import com.research.assistant.dto.research.ResearchSessionCreateRequest;
import com.research.assistant.dto.research.ResearchSessionDetail;
import com.research.assistant.dto.research.ResearchSessionSummary;
import com.research.assistant.dto.research.ResearchSessionUpdateRequest;
import com.research.assistant.service.research.ResearchSessionHistoryService;
import com.research.assistant.service.research.ResearchSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Persistent paper-research sessions and their user-visible history. */
@RestController
@RequestMapping("/api/research/sessions")
public class ResearchSessionController {

    private final ResearchSessionService sessionService;
    private final ResearchSessionHistoryService historyService;

    public ResearchSessionController(ResearchSessionService sessionService,
                                     ResearchSessionHistoryService historyService) {
        this.sessionService = sessionService;
        this.historyService = historyService;
    }

    @GetMapping
    public Result<List<ResearchSessionSummary>> list(
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") int limit) {
        historyService.backfillRecent();
        return Result.ok(sessionService.list(archived, keyword, limit));
    }

    @PostMapping
    public Result<ResearchSessionSummary> create(
            @RequestBody @Valid ResearchSessionCreateRequest request) {
        return Result.ok(sessionService.create(request));
    }

    @GetMapping("/{sessionId}")
    public Result<ResearchSessionDetail> get(@PathVariable long sessionId) {
        return Result.ok(sessionService.get(sessionId));
    }

    @PutMapping("/{sessionId}")
    public Result<ResearchSessionSummary> update(
            @PathVariable long sessionId,
            @RequestBody @Valid ResearchSessionUpdateRequest request) {
        return Result.ok(sessionService.update(sessionId, request));
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable long sessionId) {
        sessionService.delete(sessionId);
        return Result.ok();
    }

    @PostMapping("/{sessionId}/messages")
    public Result<List<ResearchMessageView>> appendMessages(
            @PathVariable long sessionId,
            @RequestBody @Valid ResearchMessageAppendRequest request) {
        return Result.ok(sessionService.appendMessages(sessionId, request));
    }

    @PostMapping("/{sessionId}/runs/{runId}")
    public Result<Void> attachRun(@PathVariable long sessionId, @PathVariable String runId) {
        sessionService.attachRun(sessionId, runId);
        return Result.ok();
    }
}
