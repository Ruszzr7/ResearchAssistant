package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.translation.TranslationRequest;
import com.research.assistant.dto.translation.TranslationResponse;
import com.research.assistant.dto.translation.TranslationStatus;
import com.research.assistant.service.translation.TranslationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/translations")
public class TranslationController {
    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @GetMapping("/status")
    public Result<TranslationStatus> status() {
        return Result.ok(translationService.status());
    }

    @PostMapping
    public Result<TranslationResponse> translate(@Valid @RequestBody TranslationRequest request) {
        return Result.ok(translationService.translate(request));
    }
}
