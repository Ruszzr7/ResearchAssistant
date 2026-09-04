package com.research.assistant.service.memory;

import java.util.List;

/** Validated model correction layered over immutable parser output. */
public record PaperLayoutRecovery(String regionId,
                                  String status,
                                  String issueType,
                                  List<String> orderedBlockIds,
                                  List<LayoutUncertainRegion.PageArea> pageAreas,
                                  String correctedText,
                                  String contentType,
                                  String provenance) {

    public PaperLayoutRecovery {
        if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("regionId is required");
        status = "CORRECTED".equalsIgnoreCase(status) ? "CORRECTED" : "UNRESOLVED";
        issueType = issueType == null ? "" : issueType;
        orderedBlockIds = orderedBlockIds == null ? List.of() : List.copyOf(orderedBlockIds);
        pageAreas = pageAreas == null ? List.of() : List.copyOf(pageAreas);
        correctedText = correctedText == null ? "" : correctedText.strip();
        contentType = contentType == null || contentType.isBlank() ? "TEXT" : contentType;
        provenance = provenance == null || provenance.isBlank() ? "VISUAL_RECOVERY" : provenance;
    }

    public boolean corrected() {
        return "CORRECTED".equals(status) && !correctedText.isBlank();
    }
}
