package com.research.assistant.service.workbench;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.research.assistant.service.pdf.layout.SelectionAnchor;

import java.util.List;

/** Validated input boundary shared by the router and persisted run trace. */
@JsonIgnoreProperties("conversationContext")
public record WorkbenchInvocation(List<Long> paperIds,
                                  String question,
                                  WorkbenchIntent intent,
                                  WorkbenchPlan.Scope requestedScope,
                                  SelectionAnchor selectionAnchor,
                                  int maxSteps,
                                  int tokenBudget,
                                  String sourceRunId,
                                  String conversationId) {
    public WorkbenchInvocation {
        paperIds = paperIds == null ? List.of() : paperIds.stream().distinct().toList();
        question = question == null ? "" : question.trim();
        intent = intent == null ? WorkbenchIntent.AUTO : intent;
        sourceRunId = sourceRunId == null ? "" : sourceRunId.trim();
        conversationId = conversationId == null ? "" : conversationId.trim();
    }

    public WorkbenchInvocation(List<Long> paperIds,
                               String question,
                               WorkbenchIntent intent,
                               WorkbenchPlan.Scope requestedScope,
                               SelectionAnchor selectionAnchor,
                               int maxSteps,
                               int tokenBudget,
                               String sourceRunId) {
        this(paperIds, question, intent, requestedScope, selectionAnchor, maxSteps, tokenBudget,
                sourceRunId, "");
    }

    public WorkbenchInvocation(List<Long> paperIds,
                               String question,
                               WorkbenchIntent intent,
                               WorkbenchPlan.Scope requestedScope,
                               SelectionAnchor selectionAnchor,
                               int maxSteps,
                               int tokenBudget) {
        this(paperIds, question, intent, requestedScope, selectionAnchor, maxSteps, tokenBudget,
                "", "");
    }
}
