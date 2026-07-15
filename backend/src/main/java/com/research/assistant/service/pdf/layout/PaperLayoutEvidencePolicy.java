package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Deterministic allow-list for blocks that may enter an AI evidence set. */
@Component
public class PaperLayoutEvidencePolicy {

    private static final Set<DocumentBlockRole> ALLOWED_ROLES = EnumSet.of(
            DocumentBlockRole.ABSTRACT,
            DocumentBlockRole.HEADING,
            DocumentBlockRole.BODY,
            DocumentBlockRole.CAPTION,
            DocumentBlockRole.FORMULA,
            DocumentBlockRole.TABLE
    );

    public boolean isAllowed(DocumentBlock block) {
        return block != null
                && ALLOWED_ROLES.contains(block.role())
                && block.text() != null
                && !block.text().isBlank();
    }

    public List<DocumentBlock> selectAllowed(PaperLayoutArtifact artifact) {
        if (artifact == null || artifact.blocks() == null) {
            return List.of();
        }
        return artifact.blocks().stream().filter(this::isAllowed).toList();
    }
}
