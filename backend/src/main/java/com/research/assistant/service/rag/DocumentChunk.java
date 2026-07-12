package com.research.assistant.service.rag;

/**
 * RAG 文档分片。
 *
 * @param paperId   所属论文 ID
 * @param chunkType 分片类型：RAW（原文）、CONTRIBUTION（核心贡献）、METHOD（方法）、FINDING（发现）、LIMITATION（局限）
 * @param content   文本内容
 * @param source    来源说明（如 "Section 3.2"）
 */
public record DocumentChunk(Long paperId, String chunkType, String content, String source,
                            String sourceType, Integer pageStart, Integer pageEnd,
                            Integer charStart, Integer charEnd, Integer chunkOrder) {

    public DocumentChunk(Long paperId, String chunkType, String content, String source) {
        this(paperId, chunkType, content, source, defaultSourceType(chunkType),
                null, null, null, null, null);
    }

    public DocumentChunk(Long paperId, String chunkType, String content, String source,
                         String sourceType, Integer pageStart, Integer pageEnd,
                         Integer charStart, Integer charEnd) {
        this(paperId, chunkType, content, source, sourceType,
                pageStart, pageEnd, charStart, charEnd, null);
    }

    public DocumentChunk withOrder(int order) {
        return new DocumentChunk(paperId, chunkType, content, source, sourceType,
                pageStart, pageEnd, charStart, charEnd, order);
    }

    private static String defaultSourceType(String chunkType) {
        return "RAW".equalsIgnoreCase(chunkType) ? "PDF_TEXT" : "ANALYSIS_FIELD";
    }
}
