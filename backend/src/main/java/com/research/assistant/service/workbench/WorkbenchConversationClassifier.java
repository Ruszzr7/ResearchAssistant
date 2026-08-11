package com.research.assistant.service.workbench;

import com.research.assistant.service.memory.PaperConversationTurn;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Deterministically classifies whether the current turn reuses the previous evidence focus. */
@Component
public class WorkbenchConversationClassifier {

    private final WorkbenchRetrievalPlanner retrievalPlanner;
    private final WorkbenchCommandPlanner commandPlanner;

    public WorkbenchConversationClassifier(WorkbenchRetrievalPlanner retrievalPlanner,
                                           WorkbenchCommandPlanner commandPlanner) {
        this.retrievalPlanner = retrievalPlanner;
        this.commandPlanner = commandPlanner;
    }

    public WorkbenchConversationRelation classify(String question,
                                                   List<PaperConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) return WorkbenchConversationRelation.NONE;
        var command = commandPlanner.parse(question);
        if (command.isPresent()) {
            return command.get().referenceMode() == WorkbenchCommandSpec.ReferenceMode.PRIOR_REFERENT
                    ? WorkbenchConversationRelation.FOLLOW_UP
                    : WorkbenchConversationRelation.INDEPENDENT;
        }

        String normalized = normalize(question);
        if (startsNewTopic(normalized)) return WorkbenchConversationRelation.INDEPENDENT;
        if (retrievalPlanner.plan(question).referentialFollowUp()
                || hasContinuationShape(normalized)) {
            return WorkbenchConversationRelation.FOLLOW_UP;
        }
        boolean related = turns.stream().skip(Math.max(0, turns.size() - 3L)).anyMatch(turn ->
                retrievalPlanner.semanticallyRelated(
                        question, turn.question() + "\n" + turn.answer()));
        return related ? WorkbenchConversationRelation.FOLLOW_UP
                : WorkbenchConversationRelation.INDEPENDENT;
    }

    private boolean startsNewTopic(String value) {
        return containsAny(value,
                "换个话题", "换一个话题", "另一个问题", "新问题", "与前文无关",
                "不考虑前文", "不沿用前文", "new topic", "unrelated question", "ignore previous");
    }

    /**
     * Covers short elliptical continuations such as alternatives, reasons and requested expansion.
     * It intentionally requires a discourse cue, so an unrelated short factual question remains independent.
     */
    private boolean hasContinuationShape(String value) {
        if (value.isBlank() || value.length() > 120) return false;
        return containsAny(value,
                "如果还", "那么", "那还", "还有", "再选", "再给", "再说", "另一", "另外",
                "第二个", "下一个", "其他的", "除此之外", "为什么呢", "具体呢", "然后呢",
                "这样呢", "哪个更", "哪一个更", "能再", "可以再", "详细说说",
                "what about", "another one", "one more", "why is that", "why so", "then what",
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
