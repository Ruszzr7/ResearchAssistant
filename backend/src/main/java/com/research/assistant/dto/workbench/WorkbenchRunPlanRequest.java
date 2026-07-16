package com.research.assistant.dto.workbench;

import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.workbench.WorkbenchIntent;
import com.research.assistant.service.workbench.WorkbenchInvocation;
import com.research.assistant.service.workbench.WorkbenchPlan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WorkbenchRunPlanRequest(
        @NotEmpty @Size(max = 8) List<@NotNull @Positive Long> paperIds,
        @Size(max = 4_000) String question,
        WorkbenchIntent intent,
        WorkbenchPlan.Scope scope,
        SelectionAnchor selectionAnchor,
        @Min(1) @Max(6) Integer maxSteps,
        @Min(256) @Max(60_000) Integer tokenBudget,
        @Size(max = 64) String sourceRunId,
        @Size(max = 64) String conversationId,
        @Size(max = 6_000) String conversationContext) {

    public WorkbenchInvocation toInvocation() {
        return new WorkbenchInvocation(
                paperIds,
                question,
                intent,
                scope,
                selectionAnchor,
                maxSteps == null ? 6 : maxSteps,
                tokenBudget == null ? 0 : tokenBudget,
                sourceRunId,
                conversationId,
                conversationContext);
    }
}
