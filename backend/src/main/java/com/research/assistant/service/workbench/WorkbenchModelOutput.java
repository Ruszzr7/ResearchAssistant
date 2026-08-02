package com.research.assistant.service.workbench;

import java.util.LinkedHashSet;
import java.util.List;

/** Normalized product output; raw provider responses are never persisted in the trace. */
public record WorkbenchModelOutput(String answer,
                                   List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                   AnnotationSuggestion annotationSuggestion,
                                   List<WorkbenchAnswerBlock> answerBlocks) {
    public WorkbenchModelOutput {
        claims = claims == null ? List.of() : List.copyOf(claims);
        answerBlocks = answerBlocks == null ? List.of() : answerBlocks.stream()
                .filter(block -> block != null && !block.text().isBlank()).toList();
        if (claims.isEmpty() && !answerBlocks.isEmpty()) claims = claimsFrom(answerBlocks);
        answer = answer == null ? "" : answer.trim();
        if (answer.isBlank() && !answerBlocks.isEmpty()) {
            answer = answerBlocks.stream().map(WorkbenchAnswerBlock::text)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
        }
    }

    public WorkbenchModelOutput(String answer,
                                List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                AnnotationSuggestion annotationSuggestion) {
        this(answer, claims, annotationSuggestion, List.of());
    }

    public WorkbenchModelOutput normalizedFor(WorkbenchPlan.Workflow workflow) {
        if (workflow != WorkbenchPlan.Workflow.ANNOTATION_SUGGESTION || annotationSuggestion != null) {
            return this;
        }
        List<String> evidenceIds = claims.stream()
                .flatMap(claim -> claim.evidenceIds().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        return new WorkbenchModelOutput(answer, claims,
                new AnnotationSuggestion(AnnotationType.COMMENT, answer, evidenceIds), answerBlocks);
    }

    public WorkbenchEvidenceGate.AnswerDraft toGateDraft(WorkbenchPlan.Workflow workflow) {
        WorkbenchModelOutput normalized = normalizedFor(workflow);
        if (workflow != WorkbenchPlan.Workflow.ANNOTATION_SUGGESTION
                || normalized.annotationSuggestion() == null) {
            return new WorkbenchEvidenceGate.AnswerDraft(normalized.answer(), normalized.claims(),
                    normalized.hasOnlyNonPaperBlocks(), normalized.answerBlocks());
        }
        List<WorkbenchEvidenceGate.GroundedClaim> gateClaims = new java.util.ArrayList<>(normalized.claims());
        gateClaims.add(new WorkbenchEvidenceGate.GroundedClaim(
                normalized.annotationSuggestion().content(), normalized.annotationSuggestion().evidenceIds()));
        return new WorkbenchEvidenceGate.AnswerDraft(normalized.answer(), gateClaims,
                normalized.hasOnlyNonPaperBlocks(), normalized.answerBlocks());
    }

    public boolean hasOnlyNonPaperBlocks() {
        return !answerBlocks.isEmpty() && answerBlocks.stream()
                .noneMatch(WorkbenchAnswerBlock::requiresPaperEvidence);
    }

    private static List<WorkbenchEvidenceGate.GroundedClaim> claimsFrom(
            List<WorkbenchAnswerBlock> blocks) {
        return blocks.stream().filter(WorkbenchAnswerBlock::requiresPaperEvidence)
                .map(block -> new WorkbenchEvidenceGate.GroundedClaim(
                        block.text(), block.citations().stream()
                        .map(WorkbenchAnswerBlock.Citation::evidenceId).distinct().toList()))
                .toList();
    }

    public enum AnnotationType {
        COMMENT,
        SUMMARY,
        QUESTION,
        CRITIQUE
    }

    public record AnnotationSuggestion(AnnotationType type, String content, List<String> evidenceIds) {
        public AnnotationSuggestion {
            type = type == null ? AnnotationType.COMMENT : type;
            content = content == null ? "" : content.trim();
            evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream()
                    .filter(id -> id != null && !id.isBlank()).distinct().toList();
        }
    }
}
