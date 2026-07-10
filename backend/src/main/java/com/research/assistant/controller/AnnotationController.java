package com.research.assistant.controller;

import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.dto.AnnotationRequest;
import com.research.assistant.service.annotation.AiAnnotationService;
import com.research.assistant.service.annotation.AnnotationService;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<AnnotationDto>> list(@PathVariable Long paperId) {
        return ResponseEntity.ok(annotationService.listByPaper(paperId));
    }

    @PostMapping
    public ResponseEntity<AnnotationDto> create(
            @PathVariable Long paperId,
            @RequestBody AnnotationRequest request) {
        return ResponseEntity.ok(annotationService.create(paperId, request));
    }

    @PutMapping("/{annotationId}")
    public ResponseEntity<AnnotationDto> update(
            @PathVariable Long annotationId,
            @RequestBody AnnotationRequest request) {
        return ResponseEntity.ok(annotationService.update(annotationId, request));
    }

    @DeleteMapping("/{annotationId}")
    public ResponseEntity<Void> delete(@PathVariable Long annotationId) {
        annotationService.delete(annotationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/ai-generate")
    public ResponseEntity<List<AnnotationDto>> aiGenerate(@PathVariable Long paperId) {
        return ResponseEntity.ok(aiAnnotationService.generateAndSave(paperId));
    }
}
