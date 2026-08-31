package com.research.assistant.service.pdf.layout;

import java.util.List;

/**
 * A deterministic reading unit assembled from adjacent layout blocks.
 * Original blocks remain the source of truth for page coordinates.
 */
public record PaperSemanticSpan(String id,
                                int page,
                                DocumentBlockRole role,
                                List<String> sectionPath,
                                String text,
                                List<DocumentBlock> blocks) {

    public PaperSemanticSpan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("span id is required");
        if (page <= 0) throw new IllegalArgumentException("span page must be positive");
        role = role == null ? DocumentBlockRole.BODY : role;
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        text = text == null ? "" : text.strip();
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        if (blocks.isEmpty()) throw new IllegalArgumentException("span requires source blocks");
    }

    public List<String> blockIds() {
        return blocks.stream().map(DocumentBlock::id).toList();
    }
}
