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
    private final PaperChunkPersistence chunkPersistence;
    private final RagIndexVersionService versionService;

    public RagIndexingService(PaperAnalysisMapper analysisMapper,
                              DocumentChunker chunker,
                              EmbeddingService embeddingService,
                              VectorStore vectorStore,
                              ResearchMetrics metrics,
                              PaperChunkPersistence chunkPersistence,
                              RagIndexVersionService versionService) {
        this.analysisMapper = analysisMapper;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.metrics = metrics;
        this.chunkPersistence = chunkPersistence;
        this.versionService = versionService;
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

        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new RagIndexingException(RagIndexingException.Reason.EMBEDDING_MISMATCH,
                    "Embedding 数量与论文分片数量不一致");
        }
        validateEmbeddings(embeddings);

        // 3. 替换旧索引
        int indexVersion;
        try {
            indexVersion = versionService.beginBuild(paperId);
        } catch (RuntimeException e) {
            throw new RagIndexingException(RagIndexingException.Reason.INDEX_VERSION_FAILED,
                    "无法创建论文 " + paperId + " 的 RAG 索引版本", e);
        }

        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);
            embeddedChunks.add(new EmbeddedChunk(c.paperId(), c.chunkType(), c.content(), c.source(),
                    embeddings.get(i), indexVersion,
                    RagChunkIdentity.chunkKey(c.paperId(), indexVersion,
                            c.chunkOrder() == null ? i : c.chunkOrder(), c.content()),
                    c.sourceType(), c.pageStart(), c.pageEnd(), c.charStart(), c.charEnd(),
                    RagChunkIdentity.contentHash(c.content())));
        }
        try {
            // 先写入不可见版本；旧版本在此期间继续提供查询服务。
            chunkPersistence.saveAll(embeddedChunks, indexVersion);
            // 先准备运行时快照，数据库 active 指针最后切换；失败时旧版本仍是 active。
            vectorStore.replacePaperIndex(paperId, indexVersion, embeddedChunks);
            versionService.activate(paperId, indexVersion, embeddedChunks.size());
        } catch (RuntimeException e) {
            versionService.markFailed(paperId, indexVersion, e.getMessage());
            throw new RagIndexingException(RagIndexingException.Reason.VECTOR_STORE_FAILED,
                    "RAG 索引版本提交失败，论文 " + paperId + " 仍保留旧版本", e);
        }
        log.info("event=rag_index_completed paperId={} chunkCount={}", paperId, embeddedChunks.size());
        return new RagIndexingResult(paperId, true, embeddedChunks.size());
    }

    private void validateEmbeddings(List<List<Float>> embeddings) {
        int dimension = -1;
        for (List<Float> embedding : embeddings) {
            if (embedding == null || embedding.isEmpty()) {
                throw new RagIndexingException(RagIndexingException.Reason.EMBEDDING_MISMATCH,
                        "Embedding 含有空向量");
            }
            if (dimension < 0) {
                dimension = embedding.size();
            } else if (embedding.size() != dimension) {
                throw new RagIndexingException(RagIndexingException.Reason.EMBEDDING_MISMATCH,
                        "Embedding 维度不一致");
            }
            for (Float value : embedding) {
                if (value == null || value.isNaN() || value.isInfinite()) {
                    throw new RagIndexingException(RagIndexingException.Reason.EMBEDDING_MISMATCH,
                            "Embedding 含有非法数值");
                }
            }
        }
    }
}
