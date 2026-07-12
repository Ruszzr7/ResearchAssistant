package com.research.assistant.dto;

/** Read-only report for verifying the MySQL active RAG version and metadata. */
public record RagConsistencyReport(
        Long paperId,
        String provider,
        String status,
        Integer activeVersion,
        int metadataChunkCount,
        Integer expectedChunkCount,
        String details
) {
}
