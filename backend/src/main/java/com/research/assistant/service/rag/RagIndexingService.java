package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.embedding.EmbeddingService;
import com.research.assistant.service.embedding.EmbeddingUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 索引服务 —— 为论文生成向量分片并写入向量存储。
 */
@Service
public class RagIndexingService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexingService.class);

    private final PaperAnalysisMapper analysisMapper;
    private final DocumentChunker chunker;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public RagIndexingService(PaperAnalysisMapper analysisMapper,
                              DocumentChunker chunker,
                              EmbeddingService embeddingService,
                              VectorStore vectorStore) {
        this.analysisMapper = analysisMapper;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /**
     * 对指定论文重建 RAG 索引。
     */
    public void indexPaper(Long paperId) {
        if (paperId == null) {
            return;
        }
        log.info("开始为论文 {} 建立 RAG 索引", paperId);
        PaperAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        if (analysis == null) {
            log.warn("论文 {} 无分析结果，跳过索引", paperId);
            return;
        }

        // 1. 分块
        List<DocumentChunk> chunks = chunker.chunk(analysis);
        if (chunks.isEmpty()) {
            log.warn("论文 {} 没有可用分片", paperId);
            return;
        }

        // 2. 生成 embedding。先完成远程调用，避免服务暂时不可用时误删旧索引。
        List<String> contents = chunks.stream().map(DocumentChunk::content).toList();
        List<List<Float>> embeddings;
        try {
            embeddings = embeddingService.embedBatch(contents);
        } catch (EmbeddingUnavailableException e) {
            log.warn("论文 {} 索引失败，Embedding 不可用: {}", paperId, e.getMessage());
            return;
        }

        if (embeddings.size() != chunks.size()) {
            log.warn("论文 {} embedding 数量与 chunk 数量不一致: {} vs {}", paperId, embeddings.size(), chunks.size());
            return;
        }

        // 3. 替换旧索引
        vectorStore.removeByPaperId(paperId);
        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);
            embeddedChunks.add(new EmbeddedChunk(c.paperId(), c.chunkType(), c.content(), c.source(), embeddings.get(i)));
        }
        vectorStore.add(embeddedChunks);
        log.info("论文 {} RAG 索引完成，共 {} 个分片", paperId, embeddedChunks.size());
    }
}
