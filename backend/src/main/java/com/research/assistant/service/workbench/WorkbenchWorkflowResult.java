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
                                      boolean contextInherited,
                                      WorkbenchContextMode contextMode) {
    public WorkbenchWorkflowResult {
        paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
        answer = answer == null ? "" : answer;
        claims = claims == null ? List.of() : List.copyOf(claims);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        answerBlocks = answerBlocks == null ? List.of() : List.copyOf(answerBlocks);
        actions = actions == null ? List.of() : List.copyOf(actions);
        contextMode = contextMode == null
                ? contextInherited ? WorkbenchContextMode.FOLLOW_UP : WorkbenchContextMode.PAPER_QUERY
                : contextMode;
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
                                   List<WorkbenchAction> actions,
                                   boolean contextInherited) {
        this(runId, workflow, scope, paperIds, answer, claims, evidence, annotationSuggestion,
                regionFallback, repairCount, answerBlocks, actions, contextInherited, null);
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
                regionFallback, repairCount, answerBlocks, actions, false, null);
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
                regionFallback, repairCount, answerBlocks, List.of(), false, null);
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
                regionFallback, repairCount, List.of(), List.of(), false, null);
    }
}
