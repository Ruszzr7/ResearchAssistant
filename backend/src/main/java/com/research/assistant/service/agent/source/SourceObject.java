package com.research.assistant.service.agent.source;

import java.util.List;
import java.util.Map;

public record SourceObject(
        String sourceObjectId,
        long paperId,
        String documentHash,
        String parserVersion,
        int sourceSchemaVersion,
        SourceContentType contentType,
        String rawContent,
        String normalizedContent,
        List<String> sectionPath,
        String formulaNumber,
        Map<String, String> provenance
) {
    public SourceObject {
        if (sourceObjectId == null || sourceObjectId.isBlank()) throw new IllegalArgumentException("sourceObjectId is required");
        if (paperId <= 0) throw new IllegalArgumentException("paperId must be positive");
        if (documentHash == null || documentHash.isBlank()) throw new IllegalArgumentException("documentHash is required");
        if (parserVersion == null || parserVersion.isBlank()) throw new IllegalArgumentException("parserVersion is required");
        contentType = contentType == null ? SourceContentType.TEXT : contentType;
        rawContent = rawContent == null ? "" : rawContent;
        normalizedContent = normalizedContent == null ? normalize(rawContent) : normalizedContent;
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        formulaNumber = formulaNumber == null ? "" : formulaNumber;
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").trim();
    }
}
