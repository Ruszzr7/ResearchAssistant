package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.LayoutEvidence;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private static final Pattern DIRECT_HIGHLIGHT = Pattern.compile(
            "(?is)^(?:请|麻烦|帮我|为我)?\\s*(?:将|把)?\\s*[“\"']?(.{1,160}?)[”\"']?"
                    + "\\s*(?:高亮|标黄|突出显示)\\s*[。！？!?.]*$");
    private static final Pattern TERM = Pattern.compile(
            "[\\p{IsLatin}\\p{IsGreek}\\p{N}_+/-]{2,}|[\\p{IsHan}]{2,}");
    private static final Pattern EQUATION_NUMBER = Pattern.compile(
            "(?i)(?:公式|方程|equation|formula)\\s*[（(]?\\s*(\\d{1,4})\\s*[)）]?");
    private static final Set<String> REFERENTIAL_TARGETS = Set.of(
            "它", "其", "这个", "这一", "那个", "上述", "前面", "该内容", "该公式",
            "这个公式", "那个公式", "it", "this", "that", "above");
    private static final Set<String> CURRENT_SELECTION_TARGETS = Set.of(
            "这段", "这段文字", "这段内容", "当前选区", "选区", "选区内容",
            "所选文字", "选中文字", "selected text", "current selection");
    private static final int MAX_ACTION_TARGETS = 12;

    public Optional<WorkbenchCommandSpec> parse(String question) {
        String target = highlightTarget(question);
        if (target.isBlank()) return Optional.empty();
        String normalized = normalize(target);
        WorkbenchCommandSpec.ReferenceMode mode = REFERENTIAL_TARGETS.stream()
                .map(this::normalize).anyMatch(normalized::equals)
                ? WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT
                : WorkbenchCommandSpec.ReferenceMode.EXPLICIT;
        return Optional.of(new WorkbenchCommandSpec(
                WorkbenchCommandSpec.Type.HIGHLIGHT, target, mode));
    }

    public List<WorkbenchAction> plan(WorkbenchRunTrace trace,
                                      WorkbenchModelOutput output,
                                      List<LayoutEvidence> evidence) {
        if (trace == null || trace.plan().workflow() != WorkbenchPlan.Workflow.SELECTION_QA) return List.of();
        Optional<WorkbenchCommandSpec> parsed = parse(trace.invocation().question());
        if (parsed.isEmpty()) return List.of();
        String target = parsed.get().target();

        List<WorkbenchAction> selectionActions = planCurrentSelection(trace, target, evidence);
        if (!selectionActions.isEmpty()) return selectionActions;

        List<CitedEvidence> cited = citedEvidence(output, evidence);
        List<TargetEvidence> targets = selectTargets(target, cited, evidence);
        if (targets.isEmpty()) {
            return List.of(new WorkbenchAction(actionId(target, "unresolved"),
                    WorkbenchAction.Type.HIGHLIGHT, WorkbenchAction.Status.UNRESOLVED,
                    trace.invocation().paperIds().get(0), "", 0, target, "",
                    "未找到经回答引用验证的精确原文，因此未执行高亮"));
        }
        return targets.stream().limit(MAX_ACTION_TARGETS).map(value -> {
            LayoutEvidence location = value.evidence();
            String targetText = isFormulaRegion(location) ? "" : value.quote();
            if (targetText.isBlank() && !isFormulaRegion(location)) targetText = readableText(location);
            return new WorkbenchAction(actionId(target, location.evidenceId()),
                    WorkbenchAction.Type.HIGHLIGHT, WorkbenchAction.Status.READY,
                    location.paperId(), location.evidenceId(), location.page(),
                    target, targetText, "已定位到引用证据，等待在 PDF 中精确高亮");
        }).toList();
    }

    /**
     * A deictic command such as “将这段文字高亮” refers to the canonical current selection.
     * It must not be converted into a whole-paper keyword search for the literal words “这段文字”.
     */
    public List<WorkbenchAction> planCurrentSelection(WorkbenchRunTrace trace,
                                                       List<LayoutEvidence> evidence) {
        if (trace == null || trace.invocation().selectionAnchor() == null) return List.of();
        Optional<WorkbenchCommandSpec> command = parse(trace.invocation().question());
        return command.map(value -> planCurrentSelection(trace, value.target(), evidence))
                .orElseGet(List::of);
    }

    private List<WorkbenchAction> planCurrentSelection(WorkbenchRunTrace trace,
                                                        String target,
                                                        List<LayoutEvidence> evidence) {
        if (trace.invocation().selectionAnchor() == null || !refersToCurrentSelection(target)) {
            return List.of();
        }
        LayoutEvidence selected = (evidence == null ? List.<LayoutEvidence>of() : evidence).stream()
                .filter(LayoutEvidence::selected)
                .filter(item -> item.page() == trace.invocation().selectionAnchor().page())
                .findFirst()
                .orElseGet(() -> (evidence == null ? List.<LayoutEvidence>of() : evidence).stream()
                        .filter(item -> trace.invocation().selectionAnchor().blockIds().contains(item.blockId()))
                        .findFirst().orElse(null));
        if (selected == null) return List.of();
        String targetText = selectedAnchorText(trace.invocation().selectionAnchor());
        return List.of(new WorkbenchAction(actionId(target, selected.evidenceId()),
                WorkbenchAction.Type.HIGHLIGHT, WorkbenchAction.Status.READY,
                selected.paperId(), selected.evidenceId(), trace.invocation().selectionAnchor().page(),
                target, targetText, "已绑定当前选区，等待在 PDF 中精确高亮"));
    }

    private boolean refersToCurrentSelection(String target) {
        String normalized = normalize(target);
        return CURRENT_SELECTION_TARGETS.stream().map(this::normalize).anyMatch(normalized::equals);
    }

    private String selectedAnchorText(com.research.assistant.service.pdf.layout.SelectionAnchor anchor) {
        if (anchor.clientTextAnchor() != null && !anchor.clientTextAnchor().contentSegments().isEmpty()) {
            String source = anchor.clientTextAnchor().contentSegments().stream()
                    .map(segment -> segment.sourceText().trim())
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .reduce((first, second) -> first + "\n" + second)
                    .orElse("");
            if (!source.isBlank()) return source;
        }
        return anchor.anchorText();
    }

    public String retrievalQuery(PaperContextSnapshot context) {
        String query = context.retrievalQuery();
        Optional<WorkbenchCommandSpec> command = parse(context.question());
        if (command.isEmpty()
                || command.get().referenceMode() == WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT) {
            return query;
        }
        StringBuilder explicit = new StringBuilder(command.get().target());
        if (!context.selectedText().isBlank()) {
            explicit.append("\n当前选区：").append(context.selectedText());
        }
        if (!context.attachmentContext().isBlank()) {
            explicit.append("\n本轮附件：").append(context.attachmentContext());
        }
        return explicit.toString();
    }

    public List<String> preferredEvidenceBlockIds(PaperContextSnapshot context) {
        Optional<WorkbenchCommandSpec> command = parse(context.question());
        if (command.isPresent()
                && command.get().referenceMode() == WorkbenchCommandSpec.ReferenceMode.EXPLICIT) {
            return List.of();
        }
        return context.preferredEvidenceBlockIds();
    }

    public String modelQuestion(PaperContextSnapshot context) {
        return modelQuestion(context, Integer.MAX_VALUE);
    }

    public String modelQuestion(PaperContextSnapshot context, int maximumCharacters) {
        Optional<WorkbenchCommandSpec> command = parse(context.question());
        if (command.isEmpty()) return context.modelQuestion(maximumCharacters);
        String instruction = "操作模式：这是受限的 PDF 高亮命令。只识别需要直接高亮的目标并引用其 evidence；"
                + "不要复述内部 blockId、bbox 或归一化坐标，不要把背景说明当成高亮目标。\n";
        int remaining = Math.max(0, maximumCharacters - instruction.length());
        if (command.get().referenceMode() == WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT) {
            return instruction + context.modelQuestion(remaining);
        }
        StringBuilder explicit = new StringBuilder("操作目标：")
                .append(command.get().target()).append('\n');
        if (!context.selectedText().isBlank()) {
            explicit.append("当前选区：").append(context.selectedText()).append('\n');
        }
        if (!context.attachmentContext().isBlank()) {
            explicit.append("本轮附件：").append(context.attachmentContext()).append('\n');
        }
        explicit.append("当前问题：").append(context.question());
        String value = explicit.toString();
        return instruction + (value.length() <= remaining ? value : value.substring(0, remaining));
    }

    public WorkbenchModelOutput userFacingOutput(WorkbenchCommandSpec command,
                                                  List<WorkbenchAction> actions,
                                                  List<LayoutEvidence> evidence) {
        List<WorkbenchAction> ready = actions.stream()
                .filter(action -> action.status() == WorkbenchAction.Status.READY).toList();
        if (ready.isEmpty()) {
            String text = "未找到可精确定位的“" + command.target() + "”，因此未执行高亮。";
            WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                    text, WorkbenchAnswerBlock.Basis.EVIDENCE_LIMIT, List.of(), List.of("r1"));
            return new WorkbenchModelOutput(text, List.of(), null, List.of(block), List.of());
        }
        Map<String, LayoutEvidence> evidenceById = new LinkedHashMap<>();
        evidence.forEach(item -> evidenceById.put(item.evidenceId(), item));
        List<WorkbenchAnswerBlock.Citation> citations = ready.stream()
                .map(action -> {
                    LayoutEvidence item = evidenceById.get(action.evidenceId());
                    if (item == null) return null;
                    String quote = isFormulaRegion(item) ? item.text() : action.targetText();
                    return new WorkbenchAnswerBlock.Citation(item.evidenceId(), quote);
                })
                .filter(java.util.Objects::nonNull).distinct().toList();
        String text = "已找到 " + ready.size() + " 处与“" + command.target()
                + "”匹配的内容，正在 PDF 中执行高亮。";
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                text, WorkbenchAnswerBlock.Basis.PAPER_FACT, citations, List.of("r1"));
        return new WorkbenchModelOutput(text, List.of(), null, List.of(block), List.of());
    }

    String highlightTarget(String question) {
        String current = currentQuestion(question);
        Matcher first = FIND_AND_HIGHLIGHT.matcher(current);
        if (first.matches()) return cleanTarget(first.group(1));
        Matcher second = HIGHLIGHT_FOUND.matcher(current);
        if (second.matches()) return cleanTarget(second.group(1));
        Matcher direct = DIRECT_HIGHLIGHT.matcher(current);
        return direct.matches() ? cleanTarget(direct.group(1)) : "";
    }

    private List<TargetEvidence> selectTargets(String target,
                                               List<CitedEvidence> cited,
                                               List<LayoutEvidence> evidence) {
        if (cited.isEmpty()) return List.of();
        boolean wantsFormula = isFormulaTarget(target);
        Set<String> requestedNumbers = equationNumbers(target);
        if (wantsFormula) {
            Map<String, TargetEvidence> formulaTargets = new LinkedHashMap<>();
            for (CitedEvidence value : cited) {
                LayoutEvidence location = formulaLocation(value.evidence(), evidence);
                if (!isFormulaRegion(location) || !matchesEquationNumber(location, requestedNumbers)) continue;
                formulaTargets.putIfAbsent(location.evidenceId(), new TargetEvidence(location, ""));
            }
            if (!formulaTargets.isEmpty()) return List.copyOf(formulaTargets.values());
        }

        double maximum = cited.stream().mapToDouble(value -> matchScore(target, value)).max().orElse(0);
        if (maximum <= 0) return List.of();
        Map<String, TargetEvidence> matches = new LinkedHashMap<>();
        cited.stream().filter(value -> matchScore(target, value) >= Math.max(0.2, maximum * 0.75))
                .forEach(value -> matches.putIfAbsent(value.evidence().evidenceId(),
                        new TargetEvidence(value.evidence(), value.quote())));
        return new ArrayList<>(matches.values());
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

    private LayoutEvidence formulaLocation(LayoutEvidence current, List<LayoutEvidence> evidence) {
        if (isFormulaRegion(current)) return current;
        String expected = "equation-region:" + current.blockId();
        return evidence.stream()
                .filter(this::isFormulaRegion)
                .filter(candidate -> candidate.blockId().equals(expected))
                .findFirst().orElse(current);
    }

    private boolean isFormulaTarget(String target) {
        String normalized = normalize(target);
        return normalized.contains("公式") || normalized.contains("方程")
                || normalized.contains("formula") || normalized.contains("equation");
    }

    private Set<String> equationNumbers(String target) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = EQUATION_NUMBER.matcher(target == null ? "" : target);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private boolean matchesEquationNumber(LayoutEvidence evidence, Set<String> requested) {
        if (requested.isEmpty()) return true;
        String corpus = String.join(" ", evidence.sectionPath()) + " " + evidence.text();
        return requested.stream().anyMatch(number -> Pattern.compile(
                "(?i)(?:equation|formula|公式|方程)\\s*[（(]?\\s*" + Pattern.quote(number)
                        + "\\s*[)）]?").matcher(corpus).find());
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
        String normalized = normalize(value);
        if (normalized.contains("信噪比")) result.addAll(List.of("sinr", "snr"));
        if (normalized.contains("信干噪比")) result.add("sinr");
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
                .replaceFirst("[：:，,\\s]+$", "")
                .replaceFirst("(?is)(?:(?:所?对应的?|对应)|(?:所?在的?))?\\s*(?:原文|内容|区域|位置)$", "")
                .trim();
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
    private record TargetEvidence(LayoutEvidence evidence, String quote) { }
}
