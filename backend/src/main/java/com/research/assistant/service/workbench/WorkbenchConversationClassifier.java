package com.research.assistant.service.workbench;

import com.research.assistant.service.memory.PaperConversationTurn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Compatibility facade for older callers. New workbench turns are routed by
 * {@link TurnRoutingManager}; keeping this facade avoids a second set of continuity rules.
 */
@Component
public class WorkbenchConversationClassifier {

    private final TurnRoutingManager routingManager;

    public WorkbenchConversationClassifier(WorkbenchRetrievalPlanner retrievalPlanner,
                                           WorkbenchCommandPlanner commandPlanner) {
        this(new TurnRoutingManager(commandPlanner, retrievalPlanner));
    }

    @Autowired
    public WorkbenchConversationClassifier(TurnRoutingManager routingManager) {
        this.routingManager = routingManager;
    }

    public WorkbenchConversationRelation classify(String question,
                                                   List<PaperConversationTurn> turns) {
        return routingManager.conversationRelation(question, turns == null ? List.of() : turns);
    }
}
