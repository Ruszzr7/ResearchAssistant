package com.research.assistant.controller;

import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.dto.AnnotationRequest;
import com.research.assistant.common.Result;
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

    public AnnotationController(AnnotationService annotationService) {
        this.annotationService = annotationService;
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
            @PathVariable Long paperId,
            @PathVariable Long annotationId,
            @RequestBody @Valid AnnotationRequest request) {
        return Result.ok(annotationService.update(paperId, annotationId, request));
    }

    @DeleteMapping("/{annotationId}")
    public Result<Void> delete(@PathVariable Long paperId, @PathVariable Long annotationId) {
        annotationService.delete(paperId, annotationId);
        return Result.ok();
    }
}
