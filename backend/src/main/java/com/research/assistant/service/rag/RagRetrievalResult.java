package com.research.assistant.service.rag;

import java.util.List;

/** 带有降级状态和版本信息的 RAG 召回结果。 */
public record RagRetrievalResult(RagRetrievalStatus status,
                                 List<ScoredChunk> chunks,
                                 Integer activeVersion,
                                 int candidateCount) {

    public RagRetrievalResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        candidateCount = Math.max(candidateCount, chunks.size());
    }

    public static RagRetrievalResult empty(RagRetrievalStatus status) {
        return new RagRetrievalResult(status, List.of(), null, 0);
    }
}
