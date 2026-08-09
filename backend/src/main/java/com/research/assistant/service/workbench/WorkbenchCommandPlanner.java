package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.LayoutEvidence;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only explicit, allow-listed PDF actions; arbitrary model-authored tools never execute. */
@Component
public class WorkbenchCommandPlanner {

    private static final Pattern FIND_AND_HIGHLIGHT = Pattern.compile(
            "(?is)^(?:请|麻烦|帮我|为我)?\\s*(?:找出|找到|定位|搜索)\\s*[“\"']?(.{1,160}?)[”\"']?"
                    + "\\s*(?:并|然后|后)?\\s*(?:将其|把它|把该内容)?\\s*(?:高亮|标黄|突出显示)\\s*[。！？!?.]*$");
    private static final Pattern HIGHLIGHT_FOUND = Pattern.compile(
            "(?is)^(?:请|麻烦|帮我|为我)?\\s*(?:将|把)?\\s*[“\"']?(.{1,160}?)[”\"']?"
                    + "\\s*(?:找出|找到|定位)\\s*(?:并|然后|后)?\\s*(?:高亮|标黄|突出显示)\\s*[。！？!?.]*$");
    private static final Pattern TERM = Pattern.compile(
            "[\\p{IsLatin}\\p{IsGreek}\\p{N}_+/-]{2,}|[\\p{IsHan}]{2,}");

    public List<WorkbenchAction> plan(WorkbenchRunTrace trace,
                                      WorkbenchModelOutput output,
                                      List<LayoutEvidence> evidence) {
        if (trace == null || trace.plan().workflow() != WorkbenchPlan.Workflow.SELECTION_QA) return List.of();
        String target = highlightTarget(trace.invocation().question());
        if (target.isBlank()) return List.of();

        List<CitedEvidence> cited = citedEvidence(output, evidence);
        CitedEvidence match = cited.stream().max(Comparator
                .comparingDouble((CitedEvidence value) -> matchScore(target, value))
                .thenComparingDouble(value -> value.evidence().score()))
                .orElse(null);
        if (match == null) {
            return List.of(new WorkbenchAction(actionId(target, "unresolved"),
                    WorkbenchAction.Type.HIGHLIGHT, WorkbenchAction.Status.UNRESOLVED,
                    trace.invocation().paperIds().get(0), "", 0, target, "",
                    "未找到经回答引用验证的精确原文，因此未执行高亮"));
        }
        LayoutEvidence location = formulaLocation(target, match, cited);
        String targetText = match.quote().isBlank()
                ? readableText(match.evidence()) : match.quote();
        return List.of(new WorkbenchAction(actionId(target, location.evidenceId()),
                WorkbenchAction.Type.HIGHLIGHT, WorkbenchAction.Status.READY,
                location.paperId(), location.evidenceId(), location.page(),
                target, targetText, "已定位到引用证据，等待在 PDF 中精确高亮"));
    }

    String highlightTarget(String question) {
        String current = currentQuestion(question);
        Matcher first = FIND_AND_HIGHLIGHT.matcher(current);
        if (first.matches()) return cleanTarget(first.group(1));
        Matcher second = HIGHLIGHT_FOUND.matcher(current);
        return second.matches() ? cleanTarget(second.group(1)) : "";
    }

    private List<CitedEvidence> citedEvidence(WorkbenchModelOutput output,
                                               List<LayoutEvidence> evidence) {
        if (output == null || evidence == null || evidence.isEmpty()) return List.of();
        Map<String, LayoutEvidence> byId = new LinkedHashMap<>();
        evidence.forEach(item -> byId.put(item.evidenceId(), item));
        Map<String, CitedEvidence> result = new LinkedHashMap<>();
        for (WorkbenchAnswerBlock block : output.answerBlocks()) {
            for (WorkbenchAnswerBlock.Citation citation : block.citations()) {
                LayoutEvidence item = byId.get(citation.evidenceId());
                if (item == null) continue;
                String key = item.evidenceId() + "|" + normalize(citation.quote());
                result.putIfAbsent(key, new CitedEvidence(item, citation.quote(), block.text()));
            }
        }
        return List.copyOf(result.values());
    }

    private double matchScore(String target, CitedEvidence value) {
        String corpus = value.quote() + " " + value.answerText() + " "
                + readableText(value.evidence()) + " " + String.join(" ", value.evidence().sectionPath());
        String normalizedTarget = normalize(target);
        String normalizedCorpus = normalize(corpus);
        if (!normalizedTarget.isBlank() && normalizedCorpus.contains(normalizedTarget)) return 3;
        Set<String> wanted = terms(target);
        Set<String> available = terms(corpus);
        if (wanted.isEmpty()) return 0;
        long matched = wanted.stream().filter(available::contains).count();
        return matched / (double) wanted.size();
    }

    private LayoutEvidence formulaLocation(String target,
                                           CitedEvidence match,
                                           List<CitedEvidence> cited) {
        String normalizedTarget = normalize(target);
        if (!normalizedTarget.contains("公式") && !normalizedTarget.contains("方程")
                && !normalizedTarget.contains("formula") && !normalizedTarget.contains("equation")) {
            return match.evidence();
        }
        LayoutEvidence current = match.evidence();
        if (isFormulaRegion(current)) return current;
        String expected = "equation-region:" + current.blockId();
        return cited.stream().map(CitedEvidence::evidence)
                .filter(this::isFormulaRegion)
                .filter(candidate -> candidate.blockId().equals(expected))
                .findFirst().orElse(current);
    }

    private boolean isFormulaRegion(LayoutEvidence evidence) {
        return evidence != null && (evidence.locator().precision()
                == com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.FORMULA_REGION
                || evidence.blockId().startsWith("equation-region:"));
    }

    private Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(normalize(value));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private String readableText(LayoutEvidence evidence) {
        String structured = evidence.structuredContent() == null ? "" : evidence.structuredContent().trim();
        return structured.isBlank() ? evidence.text().trim() : structured;
    }

    private String currentQuestion(String question) {
        String value = question == null ? "" : question.trim();
        int marker = value.lastIndexOf("当前问题：");
        return marker < 0 ? value : value.substring(marker + "当前问题：".length()).trim();
    }

    private String cleanTarget(String value) {
        String result = value == null ? "" : value.trim();
        result = result.replaceFirst("^[：:，,\\s]+", "")
                .replaceFirst("[：:，,\\s]+$", "");
        return result.length() <= 160 ? result : "";
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    private String actionId(String query, String evidenceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((query + "|" + evidenceId).getBytes(StandardCharsets.UTF_8));
            return "highlight-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record CitedEvidence(LayoutEvidence evidence, String quote, String answerText) { }
}
