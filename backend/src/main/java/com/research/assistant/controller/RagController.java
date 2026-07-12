package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.RagConsistencyReport;
import com.research.assistant.service.rag.RagConsistencyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagConsistencyService consistencyService;

    public RagController(RagConsistencyService consistencyService) {
        this.consistencyService = consistencyService;
    }

    @GetMapping("/consistency")
    public Result<List<RagConsistencyReport>> consistency(
            @RequestParam(required = false) Long paperId) {
        return Result.ok(consistencyService.check(paperId));
    }
}
