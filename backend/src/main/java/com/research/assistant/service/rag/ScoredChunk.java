package com.research.assistant.service.rag;

/**
 * 带相似度分数的检索结果。
 */
public record ScoredChunk(Long paperId, String chunkType, String content, String source, double score) {
}
