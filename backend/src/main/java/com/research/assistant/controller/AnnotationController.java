package com.research.assistant.controller;

import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.dto.AnnotationRequest;
import com.research.assistant.common.Result;
import com.research.assistant.service.annotation.AiAnnotationService;
import com.research.assistant.service.annotation.AnnotationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PDF 批注接口。
 */
@RestController
@RequestMapping("/api/papers/{paperId}/annotations")
public class AnnotationController {

    private final AnnotationService annotationService;
    private final AiAnnotationService aiAnnotationService;

    public AnnotationController(AnnotationService annotationService,
                                AiAnnotationService aiAnnotationService) {
        this.annotationService = annotationService;
        this.aiAnnotationService = aiAnnotationService;
    }

    @GetMapping
    public Result<List<AnnotationDto>> list(@PathVariable Long paperId) {
        return Result.ok(annotationService.listByPaper(paperId));
    }

    @PostMapping
    public Result<AnnotationDto> create(
            @PathVariable Long paperId,
            @RequestBody @Valid AnnotationRequest request) {
        return Result.ok(annotationService.create(paperId, request));
    }

    @PutMapping("/{annotationId}")
    public Result<AnnotationDto> update(
            @PathVariable Long annotationId,
            @RequestBody @Valid AnnotationRequest request) {
        return Result.ok(annotationService.update(annotationId, request));
    }

    @DeleteMapping("/{annotationId}")
    public Result<Void> delete(@PathVariable Long annotationId) {
        annotationService.delete(annotationId);
        return Result.ok();
    }

    @PostMapping("/ai-generate")
    public Result<List<AnnotationDto>> aiGenerate(@PathVariable Long paperId) {
        return Result.ok(aiAnnotationService.generateAndSave(paperId));
    }
}
