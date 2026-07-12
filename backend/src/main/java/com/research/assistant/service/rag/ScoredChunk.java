package com.research.assistant.service.rag;

/**
 * 带相似度分数的检索结果。
 */
public record ScoredChunk(Long paperId, String chunkType, String content, String source, double score,
                          String chunkKey, Integer indexVersion, String sourceType,
                          Integer pageStart, Integer pageEnd, Integer charStart, Integer charEnd,
                          Double rerankScore) {

    public ScoredChunk(Long paperId, String chunkType, String content, String source, double score) {
        this(paperId, chunkType, content, source, score, null, null, null,
                null, null, null, null, null);
    }

    public String evidenceId() {
        return RagChunkIdentity.evidenceId(paperId, indexVersion, chunkKey);
    }
}
