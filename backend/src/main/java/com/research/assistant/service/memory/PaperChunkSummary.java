package com.research.assistant.service.memory;

import java.time.Instant;
import java.util.List;

/** Persisted semantic summary for one deterministic paper-memory chunk. */
public record PaperChunkSummary(String chunkId,
                                String sourceFingerprint,
                                int ordinal,
                                String sectionId,
                                List<String> headingPath,
                                int pageStart,
                                int pageEnd,
                                List<String> blockIds,
                                String synopsis,
                                List<PaperMemoryClaim> claims,
                                List<String> concepts,
                                List<String> datasets,
                                List<String> models,
                                List<String> metrics,
                                String status,
                                List<String> qualityIssues,
                                int promptTokens,
                                int completionTokens,
                                String finishReason,
                                Instant generatedAt) {

    public static final String READY = "READY";
    public static final String FAILED = "FAILED";

    public PaperChunkSummary {
        chunkId = safe(chunkId);
        sourceFingerprint = safe(sourceFingerprint);
        sectionId = safe(sectionId);
        headingPath = copy(headingPath);
        blockIds = copy(blockIds);
        synopsis = safe(synopsis);
        claims = copy(claims);
        concepts = copy(concepts);
        datasets = copy(datasets);
        models = copy(models);
        metrics = copy(metrics);
        status = FAILED.equals(status) ? FAILED : READY;
        qualityIssues = copy(qualityIssues);
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        finishReason = safe(finishReason);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public boolean ready() {
        return READY.equals(status);
    }

    public static PaperChunkSummary failed(PaperMemoryChunk chunk, String issue) {
        return new PaperChunkSummary(
                chunk.id(), chunk.sourceFingerprint(), chunk.ordinal(), chunk.sectionId(),
                chunk.headingPath(), chunk.pageStart(), chunk.pageEnd(), chunk.blockIds(),
                "", List.of(), List.of(), List.of(), List.of(), List.of(), FAILED,
                List.of(issue == null || issue.isBlank() ? "CHUNK_SUMMARY_FAILED" : issue),
                0, 0, "", Instant.now());
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
