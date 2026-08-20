package com.research.assistant.service.pdf.layout;

import java.util.List;

/** A numbered equation definition and its semantic relationship to theorem/proof text. */
public record EquationEntity(String entityId,
                             String number,
                             SourceAnchor definition,
                             Relation relation,
                             String statementKind,
                             String theoremNumber,
                             List<String> contextBlockIds,
                             List<SourceAnchor> mentions) {
    public EquationEntity {
        entityId = entityId == null ? "" : entityId;
        number = number == null ? "" : number;
        relation = relation == null ? Relation.OTHER : relation;
        statementKind = statementKind == null ? "" : statementKind.trim().toUpperCase(java.util.Locale.ROOT);
        theoremNumber = theoremNumber == null ? "" : theoremNumber;
        contextBlockIds = contextBlockIds == null ? List.of() : List.copyOf(contextBlockIds);
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
    }

    public EquationEntity(String entityId,
                          String number,
                          SourceAnchor definition,
                          Relation relation,
                          String theoremNumber,
                          List<String> contextBlockIds,
                          List<SourceAnchor> mentions) {
        this(entityId, number, definition, relation, "THEOREM", theoremNumber,
                contextBlockIds, mentions);
    }

    public String statementLabel() {
        if (theoremNumber.isBlank()) return "";
        String kind = statementKind.isBlank() ? "Theorem"
                : statementKind.substring(0, 1) + statementKind.substring(1).toLowerCase(java.util.Locale.ROOT);
        return kind + " " + theoremNumber;
    }

    public enum Relation { THEOREM_RESULT, PROOF_STEP, OTHER }
}
