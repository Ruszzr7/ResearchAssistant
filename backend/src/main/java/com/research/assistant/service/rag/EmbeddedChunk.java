package com.research.assistant.service.rag;

import java.util.List;

/**
 * 已生成 embedding 的文档块。
 */
public record EmbeddedChunk(Long paperId, String chunkType, String content, String source,
                            List<Float> embedding, Integer indexVersion, String chunkKey,
                            String sourceType, Integer pageStart, Integer pageEnd,
                            Integer charStart, Integer charEnd, String contentHash) {

    public EmbeddedChunk(Long paperId, String chunkType, String content, String source,
                         List<Float> embedding) {
        this(paperId, chunkType, content, source, embedding, null,
                null, defaultSourceType(chunkType), null, null, null, null, null);
    }

    public EmbeddedChunk(Long paperId, String chunkType, String content, String source,
                         List<Float> embedding, Integer indexVersion) {
        this(paperId, chunkType, content, source, embedding, indexVersion,
                null, defaultSourceType(chunkType), null, null, null, null, null);
    }

    public EmbeddedChunk(Long paperId, String chunkType, String content, String source,
                         List<Float> embedding, Integer indexVersion, String chunkKey,
                         String sourceType, Integer pageStart, Integer pageEnd,
                         Integer charStart, Integer charEnd, String contentHash) {
        this.paperId = paperId;
        this.chunkType = chunkType;
        this.content = content;
        this.source = source;
        this.embedding = embedding;
        this.indexVersion = indexVersion;
        this.chunkKey = chunkKey;
        this.sourceType = sourceType == null || sourceType.isBlank() ? defaultSourceType(chunkType) : sourceType;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.contentHash = contentHash == null ? RagChunkIdentity.contentHash(content) : contentHash;
    }

    private static String defaultSourceType(String chunkType) {
        return "RAW".equalsIgnoreCase(chunkType) ? "PDF_TEXT" : "ANALYSIS_FIELD";
    }
}
