package com.research.assistant.service.workbench;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Workflow-specific completeness checks layered on top of citation validation. */
@Component
public class WorkbenchOutputQualityGate {

    public List<String> validate(WorkbenchPlan.Workflow workflow,
                                 WorkbenchModelOutput output,
                                 boolean structured,
                                 int paperCount) {
        List<String> issues = new ArrayList<>();
        if (!structured) issues.add("model output is not structured JSON");
        if (output == null || output.answer().isBlank()) {
            issues.add("answer is blank");
            return List.copyOf(issues);
        }
        String lower = output.answer().toLowerCase(Locale.ROOT);
        switch (workflow) {
            case SELECTION_QA -> {
                if (output.answer().length() < 12) issues.add("selection answer is too short");
                if (output.claims().isEmpty()) issues.add("selection answer has no claims");
            }
            case PAPER_ANALYSIS -> {
                if (output.answer().length() < 180) issues.add("paper analysis is too short");
                requireMarker(lower, issues, "paper analysis is missing method", "方法", "method");
                requireMarker(lower, issues, "paper analysis is missing contribution", "贡献", "contribution");
                requireMarker(lower, issues, "paper analysis is missing limitation", "局限", "limitation");
                if (output.claims().size() < 3) issues.add("paper analysis has fewer than three grounded claims");
            }
            case PAPER_COMPARISON -> {
                if (output.answer().length() < 120) issues.add("paper comparison is too short");
                if (output.answer().lines().filter(line -> line.contains("|")).count() < 3) {
                    issues.add("paper comparison is missing a comparison table");
                }
                if (output.claims().size() < paperCount) {
                    issues.add("paper comparison has too few grounded claims");
                }
            }
            case RESEARCH_GAP -> {
                if (output.answer().length() < 180) issues.add("research gap analysis is too short");
                requireMarker(lower, issues, "research gap analysis is missing candidate gaps",
                        "候选空白", "研究空白", "research gap");
                requireMarker(lower, issues, "research gap analysis is missing validation steps",
                        "验证", "verify", "validation");
                if (output.claims().size() < paperCount) {
                    issues.add("research gap analysis has too few grounded claims");
                }
            }
            case ANNOTATION_SUGGESTION -> {
                WorkbenchModelOutput.AnnotationSuggestion suggestion = output.annotationSuggestion();
                if (suggestion == null || suggestion.content().length() < 8) {
                    issues.add("annotation suggestion is missing or too short");
                } else if (suggestion.evidenceIds().isEmpty()) {
                    issues.add("annotation suggestion has no evidence");
                }
            }
        }
        return List.copyOf(issues);
    }

    private void requireMarker(String content, List<String> issues, String issue, String... markers) {
        for (String marker : markers) {
            if (content.contains(marker)) return;
        }
        issues.add(issue);
    }
}
