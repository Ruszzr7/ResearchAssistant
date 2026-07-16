package com.research.assistant.service.workbench;

import java.util.List;
import java.util.Set;

/** Immutable, allow-listed execution plan produced by the deterministic router. */
public record WorkbenchPlan(Workflow workflow,
                            Scope scope,
                            List<Step> steps,
                            Set<Skill> allowedSkills,
                            int maxSteps,
                            int tokenBudget,
                            boolean evidenceRequired,
                            int repairLimit) {

    public WorkbenchPlan {
        if (workflow == null || scope == null) throw new IllegalArgumentException("workflow and scope are required");
        steps = steps == null ? List.of() : List.copyOf(steps);
        allowedSkills = allowedSkills == null ? Set.of() : Set.copyOf(allowedSkills);
        if (steps.isEmpty() || steps.size() > maxSteps || maxSteps > 6) {
            throw new IllegalArgumentException("workbench plan exceeds the bounded step limit");
        }
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).index() != index) {
                throw new IllegalArgumentException("workbench step indexes must be contiguous");
            }
        }
        Set<Skill> actualSkills = steps.stream().map(Step::skill).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actualSkills.equals(allowedSkills)) {
            throw new IllegalArgumentException("allowed skills must exactly match the fixed workflow");
        }
        if (tokenBudget < 256 || tokenBudget > 60_000) {
            throw new IllegalArgumentException("token budget is outside the supported range");
        }
        if (repairLimit < 0 || repairLimit > 1) {
            throw new IllegalArgumentException("at most one evidence repair is allowed");
        }
    }

    public enum Workflow {
        SELECTION_QA,
        PAPER_ANALYSIS,
        PAPER_IMPROVEMENT,
        ANNOTATION_SUGGESTION,
        PAPER_COMPARISON,
        RESEARCH_GAP
    }

    public enum Scope {
        SELECTION,
        REGION,
        PAPER,
        COMPARISON
    }

    public enum StepKind {
        DETERMINISTIC,
        LLM,
        PERSISTENCE
    }

    public enum Skill {
        RESOLVE_SELECTION_CONTEXT("resolve-selection-context", StepKind.DETERMINISTIC),
        ENSURE_LAYOUT_ARTIFACT("ensure-layout-artifact", StepKind.DETERMINISTIC),
        RETRIEVE_LOCAL_EVIDENCE("retrieve-local-evidence", StepKind.DETERMINISTIC),
        RETRIEVE_PAPER_EVIDENCE("retrieve-paper-evidence", StepKind.DETERMINISTIC),
        RETRIEVE_COMPARISON_EVIDENCE("retrieve-comparison-evidence", StepKind.DETERMINISTIC),
        SYNTHESIZE_EVIDENCE_ANSWER("synthesize-evidence-answer", StepKind.LLM),
        ANALYZE_PAPER("analyze-paper-evidence", StepKind.LLM),
        IDENTIFY_PAPER_IMPROVEMENTS("identify-paper-improvements", StepKind.LLM),
        PROPOSE_ANCHORED_ANNOTATION("propose-anchored-annotation", StepKind.LLM),
        COMPARE_EVIDENCE_SET("compare-evidence-set", StepKind.LLM),
        IDENTIFY_RESEARCH_GAPS("identify-research-gaps", StepKind.LLM),
        VALIDATE_EVIDENCE_ANSWER("validate-evidence-answer", StepKind.DETERMINISTIC),
        PERSIST_ANALYSIS_REPORT("persist-analysis-report", StepKind.PERSISTENCE);

        private final String key;
        private final StepKind kind;

        Skill(String key, StepKind kind) {
            this.key = key;
            this.kind = kind;
        }

        public String key() { return key; }
        public StepKind kind() { return kind; }
    }

    public record Step(int index, String name, Skill skill, StepKind kind) {
        public Step {
            if (index < 0 || name == null || name.isBlank() || skill == null || kind == null) {
                throw new IllegalArgumentException("invalid workbench step");
            }
            if (kind != skill.kind()) {
                throw new IllegalArgumentException("step kind does not match skill policy");
            }
        }

        public static Step of(int index, String name, Skill skill) {
            return new Step(index, name, skill, skill.kind());
        }
    }

    public record ArtifactVersion(Long paperId,
                                  String documentHash,
                                  String parserVersion,
                                  double layoutConfidence) {
        public ArtifactVersion {
            if (paperId == null || paperId <= 0 || documentHash == null || documentHash.isBlank()
                    || parserVersion == null || parserVersion.isBlank()) {
                throw new IllegalArgumentException("invalid artifact version");
            }
            layoutConfidence = Math.max(0, Math.min(1, layoutConfidence));
        }
    }
}
