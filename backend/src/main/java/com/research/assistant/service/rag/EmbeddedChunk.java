package com.research.assistant.service.rag;

import java.util.List;

/**
 * 已生成 embedding 的文档块。
 */
public record EmbeddedChunk(Long paperId, String chunkType, String content, String source,
                            List<Float> embedding, Integer indexVersion) {

    public EmbeddedChunk(Long paperId, String chunkType, String content, String source,
                         List<Float> embedding) {
        this(paperId, chunkType, content, source, embedding, null);
    }
}
