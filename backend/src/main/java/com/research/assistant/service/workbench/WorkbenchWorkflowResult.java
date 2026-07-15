package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.LayoutEvidence;

import java.util.List;

/** Evidence-grounded result shared by all four fixed workbench workflows. */
public record WorkbenchWorkflowResult(String runId,
                                      WorkbenchPlan.Workflow workflow,
                                      WorkbenchPlan.Scope scope,
                                      List<Long> paperIds,
                                      String answer,
                                      List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                      List<LayoutEvidence> evidence,
                                      WorkbenchModelOutput.AnnotationSuggestion annotationSuggestion,
                                      boolean regionFallback,
                                      int repairCount) {
    public WorkbenchWorkflowResult {
        paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
        answer = answer == null ? "" : answer;
        claims = claims == null ? List.of() : List.copyOf(claims);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
