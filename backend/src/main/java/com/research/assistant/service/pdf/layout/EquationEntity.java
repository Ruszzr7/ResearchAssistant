package com.research.assistant.service.pdf.layout;

import java.util.List;

/** A numbered equation definition and its semantic relationship to theorem/proof text. */
public record EquationEntity(String entityId,
                             String number,
                             SourceAnchor definition,
                             Relation relation,
                             String theoremNumber,
                             List<String> contextBlockIds,
                             List<SourceAnchor> mentions) {
    public EquationEntity {
        entityId = entityId == null ? "" : entityId;
        number = number == null ? "" : number;
        relation = relation == null ? Relation.OTHER : relation;
        theoremNumber = theoremNumber == null ? "" : theoremNumber;
        contextBlockIds = contextBlockIds == null ? List.of() : List.copyOf(contextBlockIds);
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
    }

    public enum Relation { THEOREM_RESULT, PROOF_STEP, OTHER }
}
