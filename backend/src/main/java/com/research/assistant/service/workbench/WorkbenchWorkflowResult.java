package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.LayoutEvidence;

import java.util.List;

/** Evidence-grounded result shared by all fixed workbench workflows. */
public record WorkbenchWorkflowResult(String runId,
                                      WorkbenchPlan.Workflow workflow,
                                      WorkbenchPlan.Scope scope,
                                      List<Long> paperIds,
                                      String answer,
                                      List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                      List<LayoutEvidence> evidence,
                                      WorkbenchModelOutput.AnnotationSuggestion annotationSuggestion,
                                      boolean regionFallback,
                                      int repairCount,
                                      List<WorkbenchAnswerBlock> answerBlocks,
                                      List<WorkbenchAction> actions,
                                      boolean contextInherited) {
    public WorkbenchWorkflowResult {
        paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
        answer = answer == null ? "" : answer;
        claims = claims == null ? List.of() : List.copyOf(claims);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        answerBlocks = answerBlocks == null ? List.of() : List.copyOf(answerBlocks);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public WorkbenchWorkflowResult(String runId,
                                   WorkbenchPlan.Workflow workflow,
                                   WorkbenchPlan.Scope scope,
                                   List<Long> paperIds,
                                   String answer,
                                   List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                   List<LayoutEvidence> evidence,
                                   WorkbenchModelOutput.AnnotationSuggestion annotationSuggestion,
                                   boolean regionFallback,
                                   int repairCount,
                                   List<WorkbenchAnswerBlock> answerBlocks,
                                   List<WorkbenchAction> actions) {
        this(runId, workflow, scope, paperIds, answer, claims, evidence, annotationSuggestion,
                regionFallback, repairCount, answerBlocks, actions, false);
    }

    public WorkbenchWorkflowResult(String runId,
                                   WorkbenchPlan.Workflow workflow,
                                   WorkbenchPlan.Scope scope,
                                   List<Long> paperIds,
                                   String answer,
                                   List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                   List<LayoutEvidence> evidence,
                                   WorkbenchModelOutput.AnnotationSuggestion annotationSuggestion,
                                   boolean regionFallback,
                                   int repairCount,
                                   List<WorkbenchAnswerBlock> answerBlocks) {
        this(runId, workflow, scope, paperIds, answer, claims, evidence, annotationSuggestion,
                regionFallback, repairCount, answerBlocks, List.of(), false);
    }

    public WorkbenchWorkflowResult(String runId,
                                   WorkbenchPlan.Workflow workflow,
                                   WorkbenchPlan.Scope scope,
                                   List<Long> paperIds,
                                   String answer,
                                   List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                   List<LayoutEvidence> evidence,
                                   WorkbenchModelOutput.AnnotationSuggestion annotationSuggestion,
                                   boolean regionFallback,
                                   int repairCount) {
        this(runId, workflow, scope, paperIds, answer, claims, evidence, annotationSuggestion,
                regionFallback, repairCount, List.of(), List.of(), false);
    }
}
