package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.research.assistant.service.workbench.WorkbenchPlan.Scope;
import static com.research.assistant.service.workbench.WorkbenchPlan.Skill;
import static com.research.assistant.service.workbench.WorkbenchPlan.Step;
import static com.research.assistant.service.workbench.WorkbenchPlan.Workflow;

/** Rule-first router. It never accepts model-authored skill names or arbitrary plans. */
@Component
public class WorkbenchRuleRouter {

    public static final int MAX_PAPERS = 8;
    public static final int MAX_STEPS = 6;
    public static final int MAX_TOKEN_BUDGET = 60_000;

    public WorkbenchPlan route(WorkbenchInvocation invocation) {
        validateBase(invocation);
        Workflow workflow = chooseWorkflow(invocation);
        Scope scope = chooseScope(invocation, workflow);
        validateWorkflowInput(invocation, workflow, scope);
        List<Step> steps = stepsFor(workflow);
        if (steps.size() > invocation.maxSteps()) {
            throw new IllegalArgumentException("requested maxSteps is lower than the fixed workflow requirement");
        }
        int tokenBudget = invocation.tokenBudget() > 0 ? invocation.tokenBudget() : defaultTokenBudget(workflow);
        if (tokenBudget < 256 || tokenBudget > MAX_TOKEN_BUDGET) {
            throw new IllegalArgumentException("tokenBudget must be between 256 and 60000");
        }
        Set<Skill> allowed = steps.stream().map(Step::skill).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new WorkbenchPlan(workflow, scope, steps, allowed, invocation.maxSteps(), tokenBudget, true, 1);
    }

    private void validateBase(WorkbenchInvocation invocation) {
        if (invocation == null) throw new IllegalArgumentException("workbench invocation is required");
        if (invocation.paperIds().isEmpty() || invocation.paperIds().size() > MAX_PAPERS
                || invocation.paperIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("paperIds must contain 1 to 8 positive identifiers");
        }
        if (invocation.question().length() > 4_000) {
            throw new IllegalArgumentException("question exceeds 4000 characters");
        }
        if (invocation.maxSteps() < 1 || invocation.maxSteps() > MAX_STEPS) {
            throw new IllegalArgumentException("maxSteps must be between 1 and 6");
        }
        if (invocation.tokenBudget() < 0) {
            throw new IllegalArgumentException("tokenBudget cannot be negative");
        }
    }

    private Workflow chooseWorkflow(WorkbenchInvocation invocation) {
        return switch (invocation.intent()) {
            case ASK_SELECTION -> Workflow.SELECTION_QA;
            case ANALYZE_PAPER -> Workflow.PAPER_ANALYSIS;
            case IDENTIFY_PAPER_IMPROVEMENTS -> Workflow.PAPER_IMPROVEMENT;
            case SUGGEST_ANNOTATION -> Workflow.ANNOTATION_SUGGESTION;
            case COMPARE_PAPERS -> Workflow.PAPER_COMPARISON;
            case FIND_RESEARCH_GAPS -> Workflow.RESEARCH_GAP;
            case AUTO -> invocation.paperIds().size() > 1
                    ? Workflow.PAPER_COMPARISON
                    : invocation.selectionAnchor() != null ? Workflow.SELECTION_QA : Workflow.PAPER_ANALYSIS;
        };
    }

    private Scope chooseScope(WorkbenchInvocation invocation, Workflow workflow) {
        return switch (workflow) {
            case PAPER_ANALYSIS, PAPER_IMPROVEMENT -> Scope.PAPER;
            case PAPER_COMPARISON, RESEARCH_GAP -> Scope.COMPARISON;
            case SELECTION_QA, ANNOTATION_SUGGESTION ->
                    invocation.requestedScope() == Scope.REGION
                            || invocation.selectionAnchor() != null
                            && invocation.selectionAnchor().kind() == SelectionAnchorKind.REGION
                            ? Scope.REGION : Scope.SELECTION;
        };
    }

    private void validateWorkflowInput(WorkbenchInvocation invocation, Workflow workflow, Scope scope) {
        boolean selectionWorkflow = workflow == Workflow.SELECTION_QA || workflow == Workflow.ANNOTATION_SUGGESTION;
        if (workflow == Workflow.PAPER_COMPARISON && invocation.paperIds().size() < 2) {
            throw new IllegalArgumentException("paper comparison requires at least two papers");
        }
        if (workflow == Workflow.RESEARCH_GAP && invocation.paperIds().size() < 3) {
            throw new IllegalArgumentException("research gap analysis requires at least three papers");
        }
        if (!isMultiPaperWorkflow(workflow) && invocation.paperIds().size() != 1) {
            throw new IllegalArgumentException("this workflow accepts exactly one paper");
        }
        if (selectionWorkflow && invocation.selectionAnchor() == null) {
            throw new IllegalArgumentException("selection workflow requires a SelectionAnchor");
        }
        if (selectionWorkflow && invocation.question().isBlank()) {
            throw new IllegalArgumentException("selection workflow requires a question or instruction");
        }
        if (isMultiPaperWorkflow(workflow) && invocation.question().isBlank()) {
            throw new IllegalArgumentException("multi-paper workflow requires a question");
        }
        Scope requested = invocation.requestedScope();
        if (requested != null && !scopeCompatible(requested, scope, selectionWorkflow)) {
            throw new IllegalArgumentException("requested scope conflicts with the routed workflow");
        }
    }

    private boolean scopeCompatible(Scope requested, Scope actual, boolean selectionWorkflow) {
        if (requested == actual) return true;
        return selectionWorkflow && requested == Scope.SELECTION && actual == Scope.REGION;
    }

    private List<Step> stepsFor(Workflow workflow) {
        return switch (workflow) {
            case SELECTION_QA -> List.of(
                    Step.of(0, "解析选区", Skill.RESOLVE_SELECTION_CONTEXT),
                    Step.of(1, "检索局部证据", Skill.RETRIEVE_LOCAL_EVIDENCE),
                    Step.of(2, "生成证据回答", Skill.SYNTHESIZE_EVIDENCE_ANSWER),
                    Step.of(3, "证据门禁", Skill.VALIDATE_EVIDENCE_ANSWER));
            case PAPER_ANALYSIS -> List.of(
                    Step.of(0, "准备版面制品", Skill.ENSURE_LAYOUT_ARTIFACT),
                    Step.of(1, "检索全文证据", Skill.RETRIEVE_PAPER_EVIDENCE),
                    Step.of(2, "生成全文分析", Skill.ANALYZE_PAPER),
                    Step.of(3, "证据门禁", Skill.VALIDATE_EVIDENCE_ANSWER),
                    Step.of(4, "保存分析报告", Skill.PERSIST_ANALYSIS_REPORT));
            case PAPER_IMPROVEMENT -> List.of(
                    Step.of(0, "准备版面制品", Skill.ENSURE_LAYOUT_ARTIFACT),
                    Step.of(1, "检索全文证据", Skill.RETRIEVE_PAPER_EVIDENCE),
                    Step.of(2, "识别论文改进空间", Skill.IDENTIFY_PAPER_IMPROVEMENTS),
                    Step.of(3, "证据门禁", Skill.VALIDATE_EVIDENCE_ANSWER));
            case ANNOTATION_SUGGESTION -> List.of(
                    Step.of(0, "解析选区", Skill.RESOLVE_SELECTION_CONTEXT),
                    Step.of(1, "检索局部证据", Skill.RETRIEVE_LOCAL_EVIDENCE),
                    Step.of(2, "生成批注建议", Skill.PROPOSE_ANCHORED_ANNOTATION),
                    Step.of(3, "证据门禁", Skill.VALIDATE_EVIDENCE_ANSWER));
            case PAPER_COMPARISON -> List.of(
                    Step.of(0, "准备版面制品", Skill.ENSURE_LAYOUT_ARTIFACT),
                    Step.of(1, "检索分论文证据", Skill.RETRIEVE_COMPARISON_EVIDENCE),
                    Step.of(2, "生成证据对比", Skill.COMPARE_EVIDENCE_SET),
                    Step.of(3, "证据门禁", Skill.VALIDATE_EVIDENCE_ANSWER));
            case RESEARCH_GAP -> List.of(
                    Step.of(0, "准备版面制品", Skill.ENSURE_LAYOUT_ARTIFACT),
                    Step.of(1, "检索分论文证据", Skill.RETRIEVE_COMPARISON_EVIDENCE),
                    Step.of(2, "识别候选研究空白", Skill.IDENTIFY_RESEARCH_GAPS),
                    Step.of(3, "证据门禁", Skill.VALIDATE_EVIDENCE_ANSWER));
        };
    }

    private int defaultTokenBudget(Workflow workflow) {
        return switch (workflow) {
            case SELECTION_QA, ANNOTATION_SUGGESTION -> 6_000;
            case PAPER_ANALYSIS, PAPER_IMPROVEMENT -> 14_000;
            case PAPER_COMPARISON, RESEARCH_GAP -> 20_000;
        };
    }

    private boolean isMultiPaperWorkflow(Workflow workflow) {
        return workflow == Workflow.PAPER_COMPARISON || workflow == Workflow.RESEARCH_GAP;
    }
}
