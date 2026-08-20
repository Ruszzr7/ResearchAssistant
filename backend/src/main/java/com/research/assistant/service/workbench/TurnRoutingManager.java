package com.research.assistant.service.workbench;

import com.research.assistant.service.memory.PaperConversationTurn;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single domain routing boundary for a paper-chat turn.
 *
 * <p>Selection presence and allow-listed actions are deterministic facts. Conversation continuity
 * is considered only after action parsing, so an instruction such as “把它高亮” can never fall
 * through to the question-answering path.</p>
 */
@Component
public class TurnRoutingManager {

    private final WorkbenchCommandPlanner commandPlanner;
    private final WorkbenchRetrievalPlanner retrievalPlanner;
    private final WorkbenchRouteClassifier routeClassifier;

    public TurnRoutingManager(WorkbenchCommandPlanner commandPlanner,
                              WorkbenchRetrievalPlanner retrievalPlanner) {
        this(commandPlanner, retrievalPlanner, null);
    }

    @Autowired
    public TurnRoutingManager(WorkbenchCommandPlanner commandPlanner,
                              WorkbenchRetrievalPlanner retrievalPlanner,
                              WorkbenchRouteClassifier routeClassifier) {
        this.commandPlanner = commandPlanner;
        this.retrievalPlanner = retrievalPlanner;
        this.routeClassifier = routeClassifier;
    }

    public WorkbenchTurnRoute route(WorkbenchInvocation invocation,
                                    List<PaperConversationTurn> turns) {
        return route(invocation, turns, "");
    }

    public WorkbenchTurnRoute route(WorkbenchInvocation invocation,
                                    List<PaperConversationTurn> turns,
                                    String paperProfile) {
        if (invocation == null) throw new IllegalArgumentException("workbench invocation is required");
        List<PaperConversationTurn> history = turns == null ? List.of() : List.copyOf(turns);
        boolean hasSelection = invocation.selectionAnchor() != null;
        List<String> reasons = new ArrayList<>();
        if (hasSelection) reasons.add("current-selection-present");

        var parsedCommand = commandPlanner.parse(invocation.question());
        if (parsedCommand.isPresent()) {
            WorkbenchCommandSpec command = parsedCommand.get();
            reasons.add("allow-listed-action:" + command.type().name());
            boolean currentTarget = command.referenceMode() == WorkbenchCommandSpec.ReferenceMode.CURRENT_SELECTION
                    || hasSelection && command.referenceMode() == WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT;
            if (hasSelection && currentTarget) {
                reasons.add("action-target=current-selection");
                return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.SELECTION_ACTION,
                        command, true, false, false, false, 1, reasons);
            }
            if (command.referenceMode() == WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT
                    && !history.isEmpty()) {
                reasons.add("action-target=previous-focus");
                return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.FOLLOW_UP_ACTION,
                        command, hasSelection, true, true, false, .98, reasons);
            }
            reasons.add("action-target=paper-retrieval");
            return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.PAPER_ACTION,
                    command, hasSelection, false, true, false, .98, reasons);
        }

        if (hasSelection) {
            reasons.add("question-target=current-selection");
            boolean inherits = conversationRelation(invocation.question(), history)
                    == WorkbenchConversationRelation.FOLLOW_UP;
            if (inherits) reasons.add("selection-question=conversation-follow-up");
            return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.SELECTION_QA,
                    null, true, inherits, true, true, 1, reasons);
        }

        String normalizedQuestion = normalize(invocation.question());
        if (!history.isEmpty() && continuationShape(normalizedQuestion)) {
            reasons.add("question=conversation-follow-up");
            return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.FOLLOW_UP_QA,
                    null, false, true, true, true, .98, reasons);
        }
        if (isExplicitIndependentPaperQuery(normalizedQuestion)) {
            reasons.add("question=current-paper");
            return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.PAPER_QA,
                    null, false, false, true, true, .9, reasons);
        }
        if (!continuationShape(normalizedQuestion)
                && shouldUseSemanticFallback(invocation.question(), history)) {
            var semantic = routeClassifier == null ? java.util.Optional.<WorkbenchRouteClassifier.Decision>empty()
                    : routeClassifier.classify(invocation.question(), paperProfile, history);
            if (semantic.isPresent()) {
                var decision = semantic.get();
                reasons.add("semantic-fallback:" + decision.route().name());
                if (!decision.normalizedIntent().isBlank()) reasons.add("normalized-intent");
                return routeForSemanticDecision(decision, reasons);
            }
        }
        WorkbenchConversationRelation relation = conversationRelation(invocation.question(), history);
        if (relation == WorkbenchConversationRelation.FOLLOW_UP) {
            reasons.add("question=conversation-follow-up");
            return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.FOLLOW_UP_QA,
                    null, false, true, true, true, .92, reasons);
        }
        if (isPaperQuery(invocation.question(), paperProfile)) {
            reasons.add("question=current-paper");
            return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.PAPER_QA,
                    null, false, false, true, true, .9, reasons);
        }
        reasons.add("question=general-chat");
        return new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.GENERAL_CHAT,
                null, false, false, false, true, .82, reasons);
    }

    public WorkbenchConversationRelation conversationRelation(
            String question, List<PaperConversationTurn> turns) {
        if (turns.isEmpty()) return WorkbenchConversationRelation.NONE;
        String normalized = normalize(question);
        if (startsNewTopic(normalized)) return WorkbenchConversationRelation.INDEPENDENT;
        WorkbenchRetrievalPlan plan = retrievalPlanner.plan(question);
        if (plan.referentialFollowUp() || continuationShape(normalized)) {
            return WorkbenchConversationRelation.FOLLOW_UP;
        }
        boolean related = turns.stream().skip(Math.max(0, turns.size() - 3L)).anyMatch(turn ->
                retrievalPlanner.semanticallyRelated(
                        question, turn.question() + "\n" + turn.answer()));
        return related ? WorkbenchConversationRelation.FOLLOW_UP
                : WorkbenchConversationRelation.INDEPENDENT;
    }

    private boolean isPaperQuery(String question, String paperProfile) {
        String value = normalize(question);
        if (paperProfile != null && !paperProfile.isBlank()
                && retrievalPlanner.semanticallyRelated(question, paperProfile)) return true;
        if (containsAny(value,
                "这篇论文", "该论文", "本论文", "论文中", "论文里", "论文的",
                "这篇文章", "该文章", "本文", "文中", "文章中", "文章里", "作者",
                "current paper", "this paper", "in the paper", "the authors")) return true;
        boolean location = containsAny(value,
                "在哪", "哪里", "何处", "位置", "第几页", "哪一页", "找出", "定位",
                "where", "locate", "find", "which page");
        boolean paperObject = containsAny(value,
                "公式", "方程", "定理", "引理", "图", "表", "章节", "段落", "出处", "引用",
                "equation", "formula", "theorem", "lemma", "figure", "table", "section", "citation");
        boolean paperEvaluation = (paperObject || containsAny(value, "文章", "论文", "本文")) && containsAny(value,
                 "最重要", "最核心", "最关键", "主要", "代表", "再选", "另一",
                 "most important", "core", "key", "another", "one more");
        boolean paperConclusion = containsAny(value, "文章", "论文", "本文")
                && containsAny(value, "结论", "贡献", "方法", "结果", "发现", "创新");
        return location && paperObject || paperEvaluation || paperConclusion;
    }

    private boolean isExplicitIndependentPaperQuery(String value) {
        return containsAny(value,
                "这篇论文", "该论文", "本论文", "论文中", "论文里", "论文的",
                "这篇文章", "该文章", "本文", "文中", "文章中", "文章里",
                "current paper", "this paper", "in the paper");
    }

    private boolean shouldUseSemanticFallback(String question, List<PaperConversationTurn> history) {
        if (routeClassifier == null || history == null || history.isEmpty()) return false;
        String value = normalize(question);
        if (value.isBlank() || value.length() > 180) return false;
        return containsAny(value, "如果", "那么", "还", "再", "在选", "另", "其他", "为什么", "具体",
                "它", "这个", "上述", "前面", "what about", "another", "why", "then");
    }

    private WorkbenchTurnRoute routeForSemanticDecision(WorkbenchRouteClassifier.Decision decision,
                                                         List<String> reasons) {
        return switch (decision.route()) {
            case FOLLOW_UP_QA -> new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.FOLLOW_UP_QA,
                    null, false, true, true, true, decision.confidence(), reasons);
            case PAPER_QA -> new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.PAPER_QA,
                    null, false, false, true, true, decision.confidence(), reasons);
            default -> new WorkbenchTurnRoute(WorkbenchTurnRoute.Type.GENERAL_CHAT,
                    null, false, false, false, true, decision.confidence(), reasons);
        };
    }

    private boolean startsNewTopic(String value) {
        return containsAny(value,
                "换个话题", "换一个话题", "另一个问题", "新问题", "与前文无关",
                "不考虑前文", "不沿用前文", "new topic", "unrelated question", "ignore previous");
    }

    private boolean continuationShape(String value) {
        if (value.isBlank() || value.length() > 120) return false;
        return containsAny(value,
                "如果还", "那么", "那还", "还有", "再选", "再给", "再说", "另一", "另外",
                "第二个", "下一个", "其他的", "除此之外", "为什么呢", "具体呢", "然后呢",
                "这样呢", "哪个更", "哪一个更", "能再", "可以再", "详细说说",
                "what about", "another one", "one more", "why is that", "then what",
                "anything else", "which is better", "can you elaborate", "could you elaborate");
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
