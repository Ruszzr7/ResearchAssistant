package com.research.assistant.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 向量存储路由 —— 根据配置在 Qdrant 与内存实现之间切换，并负责失败降级。
 */
public class VectorStoreRouter implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreRouter.class);

    private final InMemoryVectorStore memory;
    private final QdrantVectorStore qdrant;
    private final boolean qdrantEnabled;

    public VectorStoreRouter(InMemoryVectorStore memory, QdrantVectorStore qdrant, boolean qdrantEnabled) {
        this.memory = memory;
        this.qdrant = qdrant;
        this.qdrantEnabled = qdrantEnabled;
    }

    @Override
    public void add(List<EmbeddedChunk> chunks) {
        if (qdrantEnabled) {
            try {
                qdrant.add(chunks);
                return;
            } catch (VectorStoreException e) {
                log.warn("Qdrant add 失败，降级到内存向量存储: {}", e.getMessage());
            }
        }
        memory.add(chunks);
    }

    @Override
    public List<ScoredChunk> findRelevant(List<Float> query, int maxResults, double minScore) {
        if (qdrantEnabled) {
            try {
                return qdrant.findRelevant(query, maxResults, minScore);
            } catch (VectorStoreException e) {
                log.warn("Qdrant findRelevant 失败，降级到内存向量存储: {}", e.getMessage());
            }
        }
        return memory.findRelevant(query, maxResults, minScore);
    }

    @Override
    public void removeByPaperId(Long paperId) {
        if (qdrantEnabled) {
            try {
                qdrant.removeByPaperId(paperId);
            } catch (VectorStoreException e) {
                log.warn("Qdrant removeByPaperId 失败，仍执行内存清理: {}", e.getMessage());
            }
        }
        memory.removeByPaperId(paperId);
    }
}
