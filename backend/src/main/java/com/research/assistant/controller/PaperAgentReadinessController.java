package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.service.agent.source.PaperAgentReadinessService;
import com.research.assistant.service.agent.source.PaperAgentReadinessView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/papers/{paperId}/readiness")
public class PaperAgentReadinessController {

    private final PaperAgentReadinessService readinessService;

    public PaperAgentReadinessController(PaperAgentReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping
    public Result<PaperAgentReadinessView> status(@PathVariable long paperId) {
        return Result.ok(readinessService.status(paperId));
    }
}
