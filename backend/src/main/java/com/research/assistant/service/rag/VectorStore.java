package com.research.assistant.service.rag;

import java.util.List;

/**
 * 向量存储抽象。
 * <p>
 * 默认使用内存实现并将分片持久化到 MySQL；也可通过配置切换为 Qdrant。
 */
public interface VectorStore {

    /**
     * 添加文档块（已含 embedding）。
     */
    void add(List<EmbeddedChunk> chunks);

    /**
     * 用指定版本替换论文的运行时索引。数据库版本指针由 RagIndexVersionService 先完成切换，
     * 此方法只负责把完整版本原子地映射到内存/外部向量库。
     */
    default void replacePaperIndex(Long paperId, int indexVersion, List<EmbeddedChunk> chunks) {
        removeByPaperId(paperId);
        add(chunks);
    }

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
