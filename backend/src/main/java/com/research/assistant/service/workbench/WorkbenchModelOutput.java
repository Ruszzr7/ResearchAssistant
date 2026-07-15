package com.research.assistant.service.workbench;

import java.util.LinkedHashSet;
import java.util.List;

/** Normalized product output; raw provider responses are never persisted in the trace. */
public record WorkbenchModelOutput(String answer,
                                   List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                   AnnotationSuggestion annotationSuggestion) {
    public WorkbenchModelOutput {
        answer = answer == null ? "" : answer.trim();
        claims = claims == null ? List.of() : List.copyOf(claims);
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
                new AnnotationSuggestion(AnnotationType.COMMENT, answer, evidenceIds));
    }

    public WorkbenchEvidenceGate.AnswerDraft toGateDraft(WorkbenchPlan.Workflow workflow) {
        WorkbenchModelOutput normalized = normalizedFor(workflow);
        if (workflow != WorkbenchPlan.Workflow.ANNOTATION_SUGGESTION
                || normalized.annotationSuggestion() == null) {
            return new WorkbenchEvidenceGate.AnswerDraft(normalized.answer(), normalized.claims());
        }
        List<WorkbenchEvidenceGate.GroundedClaim> gateClaims = new java.util.ArrayList<>(normalized.claims());
        gateClaims.add(new WorkbenchEvidenceGate.GroundedClaim(
                normalized.annotationSuggestion().content(), normalized.annotationSuggestion().evidenceIds()));
        return new WorkbenchEvidenceGate.AnswerDraft(normalized.answer(), gateClaims);
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
