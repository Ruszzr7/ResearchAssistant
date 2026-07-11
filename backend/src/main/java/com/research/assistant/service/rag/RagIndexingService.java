package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.embedding.EmbeddingService;
import com.research.assistant.service.embedding.EmbeddingUnavailableException;
import com.research.assistant.service.observability.ResearchMetrics;
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
    private final ResearchMetrics metrics;

    public RagIndexingService(PaperAnalysisMapper analysisMapper,
                              DocumentChunker chunker,
                              EmbeddingService embeddingService,
                              VectorStore vectorStore,
                              ResearchMetrics metrics) {
        this.analysisMapper = analysisMapper;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.metrics = metrics;
    }

    /**
     * 对指定论文重建 RAG 索引。
     */
    public RagIndexingResult indexPaper(Long paperId) {
        long startedAt = metrics.startTimer();
        String outcome = "failed";
        int chunkCount = 0;
        try {
            RagIndexingResult result = doIndexPaper(paperId);
            outcome = "success";
            chunkCount = result.chunkCount();
            return result;
        } catch (RagIndexingException e) {
            outcome = e.getReason().name().toLowerCase(java.util.Locale.ROOT);
            throw e;
        } finally {
            metrics.ragIndexFinished(outcome, chunkCount, startedAt);
        }
    }

    private RagIndexingResult doIndexPaper(Long paperId) {
        if (paperId == null) {
            throw new RagIndexingException(RagIndexingException.Reason.INVALID_PAPER, "论文 ID 不能为空");
        }
        log.info("event=rag_index_started paperId={}", paperId);
        PaperAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        if (analysis == null) {
            throw new RagIndexingException(RagIndexingException.Reason.ANALYSIS_MISSING,
                    "论文 " + paperId + " 尚无分析结果，无法建立索引");
        }

        // 1. 分块
        List<DocumentChunk> chunks = chunker.chunk(analysis);
        if (chunks.isEmpty()) {
            throw new RagIndexingException(RagIndexingException.Reason.NO_CHUNKS,
                    "论文 " + paperId + " 没有可用分片");
        }

        // 2. 生成 embedding。先完成远程调用，避免服务暂时不可用时误删旧索引。
        List<String> contents = chunks.stream().map(DocumentChunk::content).toList();
        List<List<Float>> embeddings;
        try {
            embeddings = embeddingService.embedBatch(contents);
        } catch (EmbeddingUnavailableException e) {
            throw new RagIndexingException(RagIndexingException.Reason.EMBEDDING_UNAVAILABLE,
                    "Embedding 服务不可用，论文 " + paperId + " 索引失败", e);
        }

        if (embeddings.size() != chunks.size()) {
            throw new RagIndexingException(RagIndexingException.Reason.EMBEDDING_MISMATCH,
                    "Embedding 数量与论文分片数量不一致");
        }

        // 3. 替换旧索引
        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);
            embeddedChunks.add(new EmbeddedChunk(c.paperId(), c.chunkType(), c.content(), c.source(), embeddings.get(i)));
        }
        try {
            vectorStore.removeByPaperId(paperId);
            vectorStore.add(embeddedChunks);
        } catch (RuntimeException e) {
            throw new RagIndexingException(RagIndexingException.Reason.VECTOR_STORE_FAILED,
                    "向量存储写入失败，论文 " + paperId + " 索引未完成", e);
        }
        log.info("event=rag_index_completed paperId={} chunkCount={}", paperId, embeddedChunks.size());
        return new RagIndexingResult(paperId, true, embeddedChunks.size());
    }
}
