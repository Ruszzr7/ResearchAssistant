package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.EvidenceOrigin;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
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
            "选定内容", "选定区域", "所选内容", "所选文字", "选中文字",
            "这段公式", "这个选区", "selected text", "current selection");
    private static final List<String> HIGHLIGHT_VERBS = List.of("突出显示", "高亮", "标黄");
    private static final List<String> UNDERLINE_VERBS = List.of("添加下划线", "加下划线", "画下划线", "下划线", "underline");
    private static final List<String> NOTE_VERBS = List.of("添加笔记", "加笔记", "写笔记", "记笔记", "note");
    private static final List<String> COMMENT_VERBS = List.of("添加批注", "加批注", "写批注", "批注", "添加注释", "加注释", "写注释", "注释", "comment");
    private static final List<String> NAVIGATE_VERBS = List.of("跳转到", "前往", "带我到", "定位到", "navigate to", "go to");
    private static final List<String> SEARCH_VERBS = List.of("找出", "找到", "定位", "搜索");
    private static final int MAX_ACTION_TARGETS = 12;

    public Optional<WorkbenchCommandSpec> parse(String question) {
        String current = currentQuestion(question);
        String normalizedQuestion = normalize(current);
        WorkbenchCommandSpec.Type type = parsedActionType(normalizedQuestion);
        if (type == null || explanatoryUse(normalizedQuestion, type)) return Optional.empty();
        String target = type == WorkbenchCommandSpec.Type.HIGHLIGHT
                ? highlightTarget(current) : actionTarget(current, type);
        Integer pageNumber = type == WorkbenchCommandSpec.Type.NAVIGATE ? pageNumber(current) : null;
        if (target.isBlank() && pageNumber == null) return Optional.empty();
        if (target.isBlank()) target = "第" + pageNumber + "页";
        String normalized = normalize(target);
        WorkbenchCommandSpec.ReferenceMode mode = refersToCurrentSelection(target)
                ? WorkbenchCommandSpec.ReferenceMode.CURRENT_SELECTION
                : refersToPriorTarget(normalized)
                ? WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT
                : WorkbenchCommandSpec.ReferenceMode.EXPLICIT;
        return Optional.of(new WorkbenchCommandSpec(
                type, target, mode, actionContent(current, type), pageNumber));
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
            return List.of(new WorkbenchAction(actionId(parsed.get().type().name() + ":" + target, "unresolved"),
                    actionType(parsed.get().type()), WorkbenchAction.Status.UNRESOLVED,
                    trace.invocation().paperIds().get(0), "", 0, target, "",
                    "未找到经引用验证的精确原文，因此未执行" + actionLabel(parsed.get().type())));
        }
        return targets.stream().limit(MAX_ACTION_TARGETS).map(value -> {
            LayoutEvidence location = value.evidence();
            String targetText = isFormulaRegion(location) ? "" : value.quote();
            if (targetText.isBlank() && !isFormulaRegion(location)) targetText = readableText(location);
            return new WorkbenchAction(actionId(parsed.get().type().name() + ":" + target, location.evidenceId()),
                    actionType(parsed.get().type()), WorkbenchAction.Status.READY,
                    location.paperId(), location.evidenceId(), location.page(),
                    target, targetText, location.locator().targetBoxes(), parsed.get().content(),
                    "已定位到引用证据，等待在 PDF 中执行" + actionLabel(parsed.get().type()));
        }).toList();
    }

    /**
     * Resolves an isolated PDF action from trusted retrieval output without asking the answer model
     * to execute a tool. The model is never allowed to invent coordinates.
     */
    public List<WorkbenchAction> planFromEvidence(WorkbenchRunTrace trace,
                                                   List<LayoutEvidence> evidence) {
        return planFromEvidence(trace, evidence, false);
    }

    public List<WorkbenchAction> planFromEvidence(WorkbenchRunTrace trace,
                                                   List<LayoutEvidence> evidence,
                                                   boolean trustedPriorFocus) {
        if (trace == null) return List.of();
        Optional<WorkbenchCommandSpec> parsed = parse(trace.invocation().question());
        if (parsed.isEmpty()) return List.of();
        if (parsed.get().type() == WorkbenchCommandSpec.Type.NAVIGATE
                && parsed.get().pageNumber() != null) {
            return List.of(navigationAction(trace, parsed.get()));
        }
        List<WorkbenchAction> selectionActions = planCurrentSelection(
                trace, parsed.get().target(), evidence);
        if (!selectionActions.isEmpty()) return selectionActions;
        List<CitedEvidence> candidates = (evidence == null ? List.<LayoutEvidence>of() : evidence).stream()
                .map(item -> new CitedEvidence(item, readableText(item), ""))
                .toList();
        List<TargetEvidence> targets = trustedPriorFocus
                ? focusedTargets(parsed.get().target(), candidates, evidence)
                : selectTargets(parsed.get().target(), candidates, evidence);
        if (targets.isEmpty()) {
            return List.of(new WorkbenchAction(actionId(parsed.get().type().name() + ":" + parsed.get().target(), "unresolved"),
                    actionType(parsed.get().type()), WorkbenchAction.Status.UNRESOLVED,
                    trace.invocation().paperIds().get(0), "", 0, parsed.get().target(), "",
                    "未找到可精确定位的论文原文，因此未执行" + actionLabel(parsed.get().type())));
        }
        return targets.stream().limit(actionTargetLimit(parsed.get().target())).map(value -> {
            LayoutEvidence location = value.evidence();
            String targetText = isFormulaRegion(location) ? "" : value.quote();
            if (targetText.isBlank() && !isFormulaRegion(location)) targetText = readableText(location);
            return new WorkbenchAction(actionId(parsed.get().type().name() + ":" + parsed.get().target(), location.evidenceId()),
                    actionType(parsed.get().type()), WorkbenchAction.Status.READY,
                    location.paperId(), location.evidenceId(), location.page(),
                    parsed.get().target(), targetText, location.locator().targetBoxes(), parsed.get().content(),
                    "已通过论文证据定位，等待执行" + actionLabel(parsed.get().type()));
        }).toList();
    }

    private List<TargetEvidence> focusedTargets(String target,
                                                List<CitedEvidence> focused,
                                                List<LayoutEvidence> evidence) {
        Map<String, TargetEvidence> result = new LinkedHashMap<>();
        if (isFormulaTarget(target)) {
            for (CitedEvidence value : focused) {
                LayoutEvidence location = formulaLocation(value.evidence(), evidence);
                if (!isFormulaRegion(location)) continue;
                result.putIfAbsent(location.evidenceId(), new TargetEvidence(location, ""));
            }
        } else {
            for (CitedEvidence value : focused) {
                if (isFormulaRegion(value.evidence())) continue;
                result.putIfAbsent(value.evidence().evidenceId(),
                        new TargetEvidence(value.evidence(), value.quote()));
            }
        }
        return List.copyOf(result.values());
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
        WorkbenchCommandSpec command = parse(trace.invocation().question()).orElseThrow();
        return List.of(new WorkbenchAction(actionId(command.type().name() + ":" + target, selected.evidenceId()),
                actionType(command.type()), WorkbenchAction.Status.READY,
                selected.paperId(), selected.evidenceId(), trace.invocation().selectionAnchor().page(),
                target, targetText, trace.invocation().selectionAnchor().boxes(), command.content(),
                "已绑定当前选区，等待在 PDF 中执行" + actionLabel(command.type())));
    }

    /** Builds a trusted selection evidence/action pair without retrieval or a model call. */
    public Optional<DirectSelectionCommand> bindCurrentSelection(WorkbenchRunTrace trace,
                                                                  PaperLayoutArtifact artifact,
                                                                  SelectionAnchor anchor) {
        if (trace == null || artifact == null || anchor == null) return Optional.empty();
        if (trace.plan().workflow() != WorkbenchPlan.Workflow.SELECTION_QA) return Optional.empty();
        Optional<WorkbenchCommandSpec> parsed = parse(trace.invocation().question());
        if (parsed.isEmpty()
                || parsed.get().referenceMode() != WorkbenchCommandSpec.ReferenceMode.CURRENT_SELECTION
                || anchor.boxes().isEmpty()) {
            return Optional.empty();
        }
        String targetText = selectedAnchorText(anchor);
        NormalizedBoundingBox bbox = union(anchor.boxes());
        boolean formulaRegion = anchor.kind() == SelectionAnchorKind.REGION && targetText.isBlank();
        String evidenceId = "selection:" + actionId(targetText, artifact.documentHash());
        EvidenceLocator locator = new EvidenceLocator(bbox, anchor.boxes(), formulaRegion ? "" : targetText,
                formulaRegion ? EvidenceLocator.Precision.FORMULA_REGION
                        : EvidenceLocator.Precision.TEXT_RANGE);
        LayoutEvidence evidence = new LayoutEvidence(
                evidenceId, artifact.paperId(), "selection:" + anchor.page(), anchor.page(), bbox,
                formulaRegion ? DocumentBlockRole.FORMULA : DocumentBlockRole.BODY,
                0, List.of("Current selection"),
                formulaRegion ? "[当前公式选区]" : targetText,
                1, true, anchor.confidence(), artifact.documentHash(), artifact.parserVersion(),
                formulaRegion ? DocumentBlockContentMode.REGION : DocumentBlockContentMode.TEXT,
                "", anchor.blockRanges(), List.of(), EvidenceOrigin.CURRENT_LAYOUT, locator,
                List.of("CURRENT_SELECTION"));
        WorkbenchAction action = new WorkbenchAction(actionId(parsed.get().type().name() + ":" + targetText, evidenceId),
                actionType(parsed.get().type()), WorkbenchAction.Status.READY,
                artifact.paperId(), evidenceId, anchor.page(), parsed.get().target(), targetText,
                anchor.boxes(), parsed.get().content(),
                "已按当前选区的原始坐标执行" + actionLabel(parsed.get().type()));
        return Optional.of(new DirectSelectionCommand(parsed.get(), evidence, action));
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
        return command.get().target();
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
        String instruction = "操作模式：这是受限的 PDF 操作命令。只识别需要执行操作的目标并引用其 evidence；"
                + "不要复述内部 blockId、bbox 或归一化坐标，不要把背景说明当成操作目标。\n";
        int remaining = Math.max(0, maximumCharacters - instruction.length());
        if (command.get().referenceMode() == WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT) {
            return instruction + context.modelQuestion(remaining);
        }
        StringBuilder explicit = new StringBuilder("操作目标：")
                .append(command.get().target()).append('\n');
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
            String text = "未找到可精确定位的“" + command.target() + "”，因此未执行"
                    + actionLabel(command.type()) + "。";
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
                + "”匹配的内容，正在 PDF 中执行" + actionLabel(command.type()) + "。";
        WorkbenchAnswerBlock block = new WorkbenchAnswerBlock(
                text, WorkbenchAnswerBlock.Basis.PAPER_FACT, citations, List.of("r1"));
        return new WorkbenchModelOutput(text, List.of(), null, List.of(block), List.of());
    }

    String highlightTarget(String question) {
        String current = currentQuestion(question);
        String normalized = normalize(current);
        String verb = HIGHLIGHT_VERBS.stream().filter(normalized::contains).findFirst().orElse("");
        if (verb.isBlank()) return "";
        // Avoid treating explanatory questions about the concept of highlighting as commands.
        if (containsAny(normalized, "为什么", "如何实现", "怎么实现", "解释")
                && SEARCH_VERBS.stream().noneMatch(normalized::contains)) return "";
        if (HIGHLIGHT_VERBS.stream().anyMatch(normalized::equals)) return "当前选区";
        for (String actionVerb : HIGHLIGHT_VERBS) {
            if (normalized.startsWith(actionVerb)) {
                String rawTarget = current.substring(actionVerb.length()).trim();
                if (refersToCurrentSelection(rawTarget)) return rawTarget;
                String value = cleanTarget(rawTarget);
                return value.isBlank() ? "当前选区" : value;
            }
        }
        Matcher first = FIND_AND_HIGHLIGHT.matcher(current);
        if (first.matches()) return cleanTarget(first.group(1));
        Matcher second = HIGHLIGHT_FOUND.matcher(current);
        if (second.matches()) return cleanTarget(second.group(1));
        Matcher direct = DIRECT_HIGHLIGHT.matcher(current);
        return direct.matches() ? cleanTarget(direct.group(1)) : "";
    }

    private WorkbenchCommandSpec.Type parsedActionType(String normalized) {
        if (COMMENT_VERBS.stream().anyMatch(normalized::contains)) return WorkbenchCommandSpec.Type.ADD_COMMENT;
        if (NOTE_VERBS.stream().anyMatch(normalized::contains)) return WorkbenchCommandSpec.Type.ADD_NOTE;
        if (UNDERLINE_VERBS.stream().anyMatch(normalized::contains)) return WorkbenchCommandSpec.Type.UNDERLINE;
        if (HIGHLIGHT_VERBS.stream().anyMatch(normalized::contains)) return WorkbenchCommandSpec.Type.HIGHLIGHT;
        if (NAVIGATE_VERBS.stream().anyMatch(normalized::contains)) return WorkbenchCommandSpec.Type.NAVIGATE;
        return null;
    }

    private boolean explanatoryUse(String normalized, WorkbenchCommandSpec.Type type) {
        boolean explanatory = containsAny(normalized,
                "为什么", "如何实现", "怎么实现", "是什么意思", "是否支持", "能否支持", "解释");
        if (!explanatory) return false;
        return SEARCH_VERBS.stream().noneMatch(normalized::contains)
                && type != WorkbenchCommandSpec.Type.NAVIGATE;
    }

    private String actionTarget(String question, WorkbenchCommandSpec.Type type) {
        String value = currentQuestion(question).trim();
        value = stripContentClause(value);
        List<String> verbs = switch (type) {
            case UNDERLINE -> UNDERLINE_VERBS;
            case ADD_NOTE -> NOTE_VERBS;
            case ADD_COMMENT -> COMMENT_VERBS;
            case NAVIGATE -> NAVIGATE_VERBS;
            case HIGHLIGHT -> HIGHLIGHT_VERBS;
        };
        String actionVerb = verbs.stream().filter(value::contains)
                .max(java.util.Comparator.comparingInt(String::length)).orElse("");
        if (actionVerb.isBlank()) return "";
        int actionIndex = value.indexOf(actionVerb);
        String before = actionIndex < 0 ? "" : value.substring(0, actionIndex);
        String after = actionIndex < 0 ? "" : value.substring(actionIndex + actionVerb.length());
        String target = cleanTarget(!before.isBlank() ? before : after);
        target = target.replaceFirst("^(?:请|麻烦|帮我|为我|将|把|给|对|增加)\\s*", "")
                .replaceFirst("^(?:找出|找到|定位|搜索)\\s*", "")
                .replaceFirst("\\s*(?:并|然后|后)$", "").trim();
        if (type == WorkbenchCommandSpec.Type.NAVIGATE && pageNumber(question) != null) {
            return "第" + pageNumber(question) + "页";
        }
        if (target.isBlank() || containsAny(target, "此处", "这里")) return "当前选区";
        return target;
    }

    private String actionContent(String question, WorkbenchCommandSpec.Type type) {
        if (type != WorkbenchCommandSpec.Type.ADD_NOTE
                && type != WorkbenchCommandSpec.Type.ADD_COMMENT) return "";
        String value = currentQuestion(question).trim();
        Matcher matcher = Pattern.compile(
                "(?is)(?:内容\\s*(?:写)?为|批注\\s*为|注释\\s*为|笔记\\s*为|[:：])\\s*[“\\\"']?(.+?)[”\\\"']?\\s*[。！？!?.]*$")
                .matcher(value);
        return matcher.find() ? matcher.group(1).replaceAll("[”\\\"']$", "").trim() : "";
    }

    private String stripContentClause(String value) {
        Matcher matcher = Pattern.compile(
                "(?is)[，,]?\\s*(?:内容\\s*(?:写)?为|批注\\s*为|注释\\s*为|笔记\\s*为|[:：]).*$")
                .matcher(value);
        return matcher.find() ? value.substring(0, matcher.start()).trim() : value;
    }

    private Integer pageNumber(String value) {
        Matcher digits = Pattern.compile("第?\\s*(\\d{1,4})\\s*页").matcher(value == null ? "" : value);
        if (digits.find()) return Integer.parseInt(digits.group(1));
        Matcher chinese = Pattern.compile("第?([一二三四五六七八九十百零两]{1,6})页").matcher(value == null ? "" : value);
        if (!chinese.find()) return null;
        return chineseNumber(chinese.group(1));
    }

    private Integer chineseNumber(String value) {
        int total = 0;
        int current = 0;
        for (char ch : value.toCharArray()) {
            int digit = "零一二三四五六七八九".indexOf(ch);
            if (ch == '两') digit = 2;
            if (digit >= 0) current = digit;
            else if (ch == '十') { total += (current == 0 ? 1 : current) * 10; current = 0; }
            else if (ch == '百') { total += (current == 0 ? 1 : current) * 100; current = 0; }
        }
        return total + current;
    }

    private WorkbenchAction navigationAction(WorkbenchRunTrace trace, WorkbenchCommandSpec command) {
        int page = command.pageNumber();
        return new WorkbenchAction(actionId("NAVIGATE:" + page, "page"),
                WorkbenchAction.Type.NAVIGATE, WorkbenchAction.Status.READY,
                trace.invocation().paperIds().get(0), "", page, command.target(), "",
                List.of(), "", "正在跳转到第 " + page + " 页");
    }

    private int actionTargetLimit(String target) {
        String value = normalize(target);
        return containsAny(value, "全部", "所有", "两条", "多个", "all", "both")
                ? Math.min(6, MAX_ACTION_TARGETS) : 1;
    }

    private int firstContentMarker(String value) {
        int chinese = value.indexOf('：');
        int ascii = value.indexOf(':');
        if (chinese < 0) return ascii;
        if (ascii < 0) return chinese;
        return Math.min(chinese, ascii);
    }

    private boolean refersToPriorTarget(String normalized) {
        if (REFERENTIAL_TARGETS.stream().map(this::normalize).anyMatch(normalized::equals)) return true;
        return containsAny(normalized,
                "刚才", "上一个", "上一条", "前一条", "前述", "之前的", "上一轮",
                "previous", "last one", "earlier");
    }

    private WorkbenchAction.Type actionType(WorkbenchCommandSpec.Type type) {
        return WorkbenchAction.Type.valueOf(type.name());
    }

    private String actionLabel(WorkbenchCommandSpec.Type type) {
        return switch (type) {
            case HIGHLIGHT -> "高亮";
            case UNDERLINE -> "下划线";
            case ADD_NOTE -> "笔记";
            case ADD_COMMENT -> "批注";
            case NAVIGATE -> "跳转";
        };
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private NormalizedBoundingBox union(List<NormalizedBoundingBox> boxes) {
        double left = boxes.stream().mapToDouble(NormalizedBoundingBox::x).min().orElse(0);
        double top = boxes.stream().mapToDouble(NormalizedBoundingBox::y).min().orElse(0);
        double right = boxes.stream().mapToDouble(NormalizedBoundingBox::right).max().orElse(left);
        double bottom = boxes.stream().mapToDouble(NormalizedBoundingBox::bottom).max().orElse(top);
        return new NormalizedBoundingBox(left, top, right - left, bottom - top);
    }

    public record DirectSelectionCommand(WorkbenchCommandSpec command,
                                         LayoutEvidence evidence,
                                         WorkbenchAction action) { }

    private List<TargetEvidence> selectTargets(String target,
                                               List<CitedEvidence> cited,
                                               List<LayoutEvidence> evidence) {
        if (cited.isEmpty()) return List.of();
        boolean wantsFormula = isFormulaTarget(target);
        Set<String> requestedNumbers = equationNumbers(target);
        if (wantsFormula) {
            Map<String, TargetEvidence> formulaTargets = new LinkedHashMap<>();
            double maximum = cited.stream().mapToDouble(value -> matchScore(target, value)).max().orElse(0);
            boolean formulaOnly = cited.stream().allMatch(value -> isFormulaRegion(value.evidence()));
            double topFormulaScore = cited.stream().filter(value -> isFormulaRegion(value.evidence()))
                    .mapToDouble(value -> value.evidence().score()).max().orElse(0);
            List<CitedEvidence> ranked = cited.stream()
                    .sorted(java.util.Comparator
                            .comparingDouble((CitedEvidence value) -> matchScore(target, value)).reversed()
                            .thenComparing(java.util.Comparator
                                    .comparingDouble((CitedEvidence value) -> value.evidence().score()).reversed()))
                    .toList();
            for (CitedEvidence value : ranked) {
                LayoutEvidence location = formulaLocation(value.evidence(), evidence);
                if (!isFormulaRegion(location) || !matchesEquationNumber(location, requestedNumbers)) continue;
                boolean semanticallyMatched = maximum > 0
                        && matchScore(target, value) >= Math.max(0.2, maximum * 0.70);
                Set<String> citedNumbers = equationNumbers(value.answerText());
                boolean explicitlyCitedFormula = isFormulaRegion(value.evidence())
                        && !citedNumbers.isEmpty() && matchesEquationNumber(location, citedNumbers);
                boolean retrievalOnlyFormula = formulaOnly && topFormulaScore > 0
                        && value.evidence().score() >= topFormulaScore * 0.90;
                if (requestedNumbers.isEmpty()
                        && !semanticallyMatched && !explicitlyCitedFormula && !retrievalOnlyFormula) continue;
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
        Set<String> wanted = semanticNeedles(target);
        if (wanted.isEmpty()) return 0;
        long matched = wanted.stream()
                .filter(needle -> normalizedCorpus.contains(normalize(needle)))
                .count();
        return matched / (double) wanted.size();
    }

    private LayoutEvidence formulaLocation(LayoutEvidence current, List<LayoutEvidence> evidence) {
        if (isFormulaRegion(current)) return current;
        String expected = "equation-region:" + current.blockId();
        LayoutEvidence direct = evidence.stream()
                .filter(this::isFormulaRegion)
                .filter(candidate -> candidate.blockId().equals(expected))
                .findFirst().orElse(null);
        if (direct != null) return direct;
        return evidence.stream()
                .filter(this::isFormulaRegion)
                .filter(candidate -> candidate.paperId().equals(current.paperId()))
                .filter(candidate -> candidate.page() == current.page())
                .filter(candidate -> sameReadingFlow(current, candidate))
                .min(java.util.Comparator
                        .comparingDouble((LayoutEvidence candidate) -> formulaDistance(current, candidate))
                        .thenComparingInt(LayoutEvidence::readingOrder))
                .orElse(current);
    }

    private boolean sameReadingFlow(LayoutEvidence first, LayoutEvidence second) {
        double overlap = Math.max(0,
                Math.min(first.bbox().right(), second.bbox().right())
                        - Math.max(first.bbox().x(), second.bbox().x()));
        return overlap >= Math.min(first.bbox().width(), second.bbox().width()) * 0.20;
    }

    private double formulaDistance(LayoutEvidence context, LayoutEvidence formula) {
        double readingDistance = Math.abs(context.readingOrder() - formula.readingOrder()) * 0.04;
        double verticalDistance = formula.bbox().y() >= context.bbox().bottom()
                ? formula.bbox().y() - context.bbox().bottom()
                : context.bbox().y() >= formula.bbox().bottom()
                ? context.bbox().y() - formula.bbox().bottom() : 0;
        double precedingPenalty = formula.readingOrder() < context.readingOrder() ? 0.03 : 0;
        return readingDistance + verticalDistance + precedingPenalty;
    }

    private boolean isFormulaTarget(String target) {
        String normalized = normalize(target);
        return normalized.contains("公式") || normalized.contains("方程")
                || normalized.contains("表达式") || normalized.contains("闭式")
                || normalized.contains("formula") || normalized.contains("equation")
                || normalized.contains("expression") || normalized.contains("closed-form");
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

    private Set<String> semanticNeedles(String value) {
        String normalized = normalize(value);
        Set<String> result = new LinkedHashSet<>(terms(value));
        result.removeIf(term -> isFormulaTarget(term)
                || HIGHLIGHT_VERBS.stream().anyMatch(verb -> normalize(verb).equals(term))
                || UNDERLINE_VERBS.stream().anyMatch(verb -> normalize(verb).equals(term))
                || NOTE_VERBS.stream().anyMatch(verb -> normalize(verb).equals(term))
                || COMMENT_VERBS.stream().anyMatch(verb -> normalize(verb).equals(term))
                || NAVIGATE_VERBS.stream().anyMatch(verb -> normalize(verb).equals(term))
                || SEARCH_VERBS.stream().anyMatch(verb -> normalize(verb).equals(term)));
        if (normalized.contains("公共流")) result.addAll(List.of("commonstream", "common-stream"));
        if (normalized.contains("私有流")) result.addAll(List.of("privatestream", "private-stream"));
        if (normalized.contains("速率")) result.addAll(List.of("rate", "ergodicrate", "achievablerate"));
        if (normalized.contains("闭式")) result.addAll(List.of("closedform", "expression"));
        if (normalized.contains("信噪比") || normalized.contains("信干噪比")) result.add("sinr");
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
            return "pdf-action-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record CitedEvidence(LayoutEvidence evidence, String quote, String answerText) { }
    private record TargetEvidence(LayoutEvidence evidence, String quote) { }
}
