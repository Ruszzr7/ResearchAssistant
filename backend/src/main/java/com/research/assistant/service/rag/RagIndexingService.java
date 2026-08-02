package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.observability.ResearchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Builds a local text index. No embedding or remote model call occurs during indexing. */
@Service
public class RagIndexingService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexingService.class);

    private final PaperAnalysisMapper analysisMapper;
    private final DocumentChunker chunker;
    private final ResearchMetrics metrics;
    private final PaperChunkPersistence chunkPersistence;
    private final RagIndexVersionService versionService;

    public RagIndexingService(PaperAnalysisMapper analysisMapper,
                              DocumentChunker chunker,
                              ResearchMetrics metrics,
                              PaperChunkPersistence chunkPersistence,
                              RagIndexVersionService versionService) {
        this.analysisMapper = analysisMapper;
        this.chunker = chunker;
        this.metrics = metrics;
        this.chunkPersistence = chunkPersistence;
        this.versionService = versionService;
    }

    public RagIndexingResult indexPaper(Long paperId) {
        long startedAt = metrics.startTimer();
        String outcome = "failed";
        int chunkCount = 0;
        try {
            RagIndexingResult result = doIndexPaper(paperId);
            outcome = "success";
            chunkCount = result.chunkCount();
            return result;
        } catch (RagIndexingException error) {
            outcome = error.getReason().name().toLowerCase(java.util.Locale.ROOT);
            throw error;
        } finally {
            metrics.ragIndexFinished(outcome, chunkCount, startedAt);
        }
    }

    private RagIndexingResult doIndexPaper(Long paperId) {
        if (paperId == null) {
            throw new RagIndexingException(RagIndexingException.Reason.INVALID_PAPER, "论文 ID 不能为空");
        }
        PaperAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        if (analysis == null) {
            throw new RagIndexingException(RagIndexingException.Reason.ANALYSIS_MISSING,
                    "论文 " + paperId + " 尚无分析结果，无法建立本地索引");
        }
        List<DocumentChunk> chunks = chunker.chunk(analysis);
        if (chunks.isEmpty()) {
            throw new RagIndexingException(RagIndexingException.Reason.NO_CHUNKS,
                    "论文 " + paperId + " 没有可用分片");
        }
        int version;
        try {
            version = versionService.beginBuild(paperId);
            chunkPersistence.saveAll(chunks, version);
            versionService.activate(paperId, version, chunks.size());
        } catch (RuntimeException error) {
            throw new RagIndexingException(RagIndexingException.Reason.INDEX_VERSION_FAILED,
                    "论文 " + paperId + " 的本地索引提交失败", error);
        }
        log.info("event=local_text_index_completed paperId={} chunkCount={}", paperId, chunks.size());
        return new RagIndexingResult(paperId, true, chunks.size());
    }
}
