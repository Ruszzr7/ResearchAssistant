package com.research.assistant.service.rag;

import java.util.List;

/**
 * 向量存储抽象。
 * <p>
 * 第一阶段默认使用内存实现并持久化到 MySQL；后续可替换为 pgvector / Qdrant。
 */
public interface VectorStore {

    /**
     * 添加文档块（已含 embedding）。
     */
    void add(List<EmbeddedChunk> chunks);

    /**
     * 检索与查询向量最相关的文档块。
     *
     * @param query     查询向量
     * @param maxResults 最大返回数
     * @param minScore  最低相似度阈值（0-1）
     * @return 带分数的结果列表，按分数降序
     */
    List<ScoredChunk> findRelevant(List<Float> query, int maxResults, double minScore);

    /**
     * 清空某篇论文的所有 chunk。
     */
    void removeByPaperId(Long paperId);
}
