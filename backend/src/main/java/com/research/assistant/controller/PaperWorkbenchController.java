package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.workbench.LocalEvidenceRequest;
import com.research.assistant.dto.workbench.SelectionAnchorRequest;
import com.research.assistant.service.pdf.layout.LocalEvidenceResult;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deterministic selection and evidence endpoints used by the PDF workbench. */
@RestController
@RequestMapping("/api/papers/{paperId}/workbench")
public class PaperWorkbenchController {

    private final PaperLayoutArtifactService artifactService;
    private final SelectionAnchorResolver anchorResolver;
    private final PaperLayoutEvidenceService evidenceService;

    public PaperWorkbenchController(PaperLayoutArtifactService artifactService,
                                    SelectionAnchorResolver anchorResolver,
                                    PaperLayoutEvidenceService evidenceService) {
        this.artifactService = artifactService;
        this.anchorResolver = anchorResolver;
        this.evidenceService = evidenceService;
    }

    @PostMapping("/selection-anchor")
    public Result<SelectionAnchor> resolveSelection(
            @PathVariable Long paperId,
            @Valid @RequestBody SelectionAnchorRequest request) {
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
        SelectionAnchor anchor = anchorResolver.resolve(
                artifact,
                request.page(),
                request.boxes().stream().map(box -> box.toBoundingBox()).toList(),
                request.anchorText(),
                request.preferredKind());
        return Result.ok(anchor);
    }

    @PostMapping("/evidence/local")
    public Result<LocalEvidenceResult> retrieveLocalEvidence(
            @PathVariable Long paperId,
            @Valid @RequestBody LocalEvidenceRequest request) {
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
        return Result.ok(evidenceService.retrieve(
                artifact, request.anchor(), request.query(), request.safeMaxResults()));
    }
}
