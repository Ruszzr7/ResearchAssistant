package com.research.assistant.service.ai;

import com.research.assistant.service.observability.ResearchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic quality boundary for compare-papers and analyze-gaps reports.
 * It validates structure and completeness only; it never invents report content.
 */
@Component
public class ResearchSynthesisQualityGate {

    private static final int MAX_REPORT_LENGTH = 60_000;
    private static final Pattern GAP_HEADING = Pattern.compile(
            "(?im)^\\s*#{2,6}\\s*.*\\bGap\\b.*$");
    private static final Set<String> COMPARE_MARKERS = Set.of(
            "compare", "comparison", "对比", "比较", "方法", "method", "dataset", "数据", "性能", "performance");
    private static final Set<String> GAP_DIMENSIONS = Set.of(
            "方法", "场景", "数据", "理论", "比较", "交叉",
            "method", "scenario", "data", "theory", "comparison", "cross");
    private static final Set<String> GAP_ACTION_MARKERS = Set.of(
            "验证", "方向", "建议", "研究", "future", "research", "validate", "direction");

    private final ResearchMetrics metrics;

    @Autowired
    public ResearchSynthesisQualityGate(ResearchMetrics metrics) {
        this.metrics = metrics;
    }

    public ResearchSynthesisQualityGate() {
        this(new ResearchMetrics(new SimpleMeterRegistry()));
    }

    public QualityReport validateCompare(String content, int paperCount) {
        List<String> issues = new ArrayList<>();
        Normalized normalized = normalize(content, issues);
        String lower = normalized.content().toLowerCase(Locale.ROOT);
        if (paperCount < 2) {
            issues.add("compare requires at least two papers");
        }
        if (normalized.content().length() < 80) {
            issues.add("compare report is too short");
        }
        long tableRows = normalized.content().lines().filter(line -> line.contains("|")).count();
        if (tableRows < 3) {
            issues.add("compare report is missing a markdown comparison table");
        }
        if (COMPARE_MARKERS.stream().noneMatch(lower::contains)) {
            issues.add("compare report is missing comparison dimensions");
        }
        return finish("compare", normalized, issues);
    }

    public QualityReport validateGaps(String content) {
        List<String> issues = new ArrayList<>();
        Normalized normalized = normalize(content, issues);
        String lower = normalized.content().toLowerCase(Locale.ROOT);
        long gapCount = GAP_HEADING.matcher(normalized.content()).results().count();
        if (normalized.content().length() < 120) {
            issues.add("gap report is too short");
        }
        if (gapCount < 3) {
            issues.add("gap report must contain at least three Gap headings");
        }
        long dimensionCount = GAP_DIMENSIONS.stream().filter(lower::contains).count();
        if (dimensionCount < 3) {
            issues.add("gap report covers fewer than three research dimensions");
        }
        if (GAP_ACTION_MARKERS.stream().noneMatch(lower::contains)) {
            issues.add("gap report is missing a research direction or verification recommendation");
        }
        return finish("gap", normalized, issues);
    }

    private QualityReport finish(String operation, Normalized normalized, List<String> issues) {
        if (normalized.content().length() > MAX_REPORT_LENGTH) {
            issues.add("report exceeds maximum length");
        }
        boolean valid = !normalized.content().isBlank() && issues.isEmpty();
        String status = valid ? (normalized.repaired() ? "repaired" : "passed") : "rejected";
        metrics.synthesisQualityFinished(operation, status);
        return new QualityReport(valid, normalized.repaired(), normalized.content(), List.copyOf(issues));
    }

    private Normalized normalize(String content, List<String> issues) {
        if (content == null) {
            issues.add("report is null");
            return new Normalized("", false);
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        normalized = normalized.replaceAll("\\n{4,}", "\\n\\n\\n");
        return new Normalized(normalized, !Objects.equals(content, normalized));
    }

    public record QualityReport(boolean valid, boolean repaired, String content, List<String> issues) {
        public QualityReport {
            content = content == null ? "" : content;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private record Normalized(String content, boolean repaired) {
    }
}
