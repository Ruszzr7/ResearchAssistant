package com.research.assistant.service.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Adaptive understanding: one bounded whole-paper call when possible, resumable map-reduce otherwise. */
@Service
public class PaperUnderstandingService {

    public static final String PIPELINE_VERSION = "paper-understanding-v2";
    public static final String STATUS_UNDERSTANDING = "UNDERSTANDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    private static final Logger log = LoggerFactory.getLogger(PaperUnderstandingService.class);
    private static final TypeReference<List<PaperChunkSummary>> SUMMARY_LIST = new TypeReference<>() { };

    private final PaperMemoryService memoryService;
    private final PaperMemoryMapper memoryMapper;
    private final PaperLayoutArtifactService artifactService;
    private final PaperMemoryChunker chunker;
    private final PaperMemoryModelService modelService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;

    public PaperUnderstandingService(PaperMemoryService memoryService,
                                     PaperMemoryMapper memoryMapper,
                                     PaperLayoutArtifactService artifactService,
                                     PaperMemoryChunker chunker,
                                     PaperMemoryModelService modelService,
                                     ObjectMapper objectMapper,
                                     @Qualifier("paperMemoryExecutor") ThreadPoolTaskExecutor executor) {
        this.memoryService = memoryService;
        this.memoryMapper = memoryMapper;
        this.artifactService = artifactService;
        this.chunker = chunker;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public PaperUnderstandingResult understand(Long paperId,
                                                boolean forceRefresh,
                                                Consumer<String> stageUpdater) {
        PaperMemoryState state = memoryService.ensureStructure(paperId, false);
        PaperMemoryRecord record = memoryMapper.selectVersion(
                paperId, state.documentHash(), state.layoutParserVersion(), state.schemaVersion());
        if (record == null) throw new IllegalStateException("论文结构记忆未持久化");
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
        List<PaperMemoryChunk> chunks = chunker.chunk(state.structure(), artifact);
        if (chunks.isEmpty()) {
            finish(record, STATUS_FAILED, List.of(), null, 0, 0,
                    "NO_UNDERSTANDING_CHUNKS", "未找到可理解的论文正文", true);
            return result(record, List.of(), null);
        }

        List<PaperChunkSummary> cached = compatibleSummaries(record, chunks, forceRefresh);
        PaperGlobalProfile cachedProfile = compatibleProfile(record, forceRefresh);
        if (!forceRefresh && STATUS_READY.equals(record.getStatus())
                && cached.size() == chunks.size() && cachedProfile != null) {
            return result(record, cached, cachedProfile);
        }

        Map<String, PaperChunkSummary> summaries = new LinkedHashMap<>();
        for (PaperChunkSummary summary : cached) {
            if (summary.ready()) summaries.put(summary.chunkId(), summary);
        }
        List<PaperMemoryChunk> pending = chunks.stream()
                .filter(chunk -> !summaries.containsKey(chunk.id()))
                .toList();
        initialize(record, chunks.size(), summaries.values());
        stage(stageUpdater, progressText(summaries.size(), chunks.size()));

        if (chunks.size() == 1 && pending.size() == 1 && summaries.isEmpty()) {
            return understandWholePaper(
                    state, record, chunks.get(0), stageUpdater);
        }

        if (!pending.isEmpty()) {
            CompletionService<PaperChunkSummary> completion = new ExecutorCompletionService<>(
                    executor.getThreadPoolExecutor());
            List<Future<PaperChunkSummary>> futures = new ArrayList<>();
            for (PaperMemoryChunk chunk : pending) {
                futures.add(completion.submit(() -> summarizeSafely(chunk)));
            }
            try {
                for (int index = 0; index < pending.size(); index++) {
                    PaperChunkSummary summary = completion.take().get();
                    summaries.put(summary.chunkId(), summary);
                    List<PaperChunkSummary> ordered = ordered(summaries.values());
                    checkpoint(record, chunks.size(), ordered);
                    stage(stageUpdater, progressText(
                            readyCount(ordered) + failedCount(ordered), chunks.size()));
                }
            } catch (InterruptedException exception) {
                futures.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                List<PaperChunkSummary> ordered = ordered(summaries.values());
                finish(record, readyCount(ordered) > 0 ? STATUS_PARTIAL : STATUS_FAILED,
                        ordered, null, promptTokens(ordered), completionTokens(ordered),
                        "UNDERSTANDING_INTERRUPTED", "论文理解已中断", true);
                throw new IllegalStateException("论文理解已中断", exception);
            } catch (Exception exception) {
                futures.forEach(future -> future.cancel(true));
                List<PaperChunkSummary> ordered = ordered(summaries.values());
                finish(record, readyCount(ordered) > 0 ? STATUS_PARTIAL : STATUS_FAILED,
                        ordered, null, promptTokens(ordered), completionTokens(ordered),
                        "UNDERSTANDING_EXECUTION_FAILED", "论文分块任务执行失败", true);
                throw new IllegalStateException("论文分块任务执行失败", exception);
            }
        }

        List<PaperChunkSummary> ordered = ordered(summaries.values());
        int ready = readyCount(ordered);
        int failed = failedCount(ordered);
        if (ready == 0) {
            finish(record, STATUS_FAILED, ordered, null,
                    promptTokens(ordered), completionTokens(ordered),
                    "ALL_CHUNKS_FAILED", "所有论文分块均理解失败", true);
            return result(record, ordered, null);
        }
        if (failed > 0) {
            finish(record, STATUS_PARTIAL, ordered, null,
                    promptTokens(ordered), completionTokens(ordered),
                    "CHUNK_SUMMARY_PARTIAL",
                    "部分章节理解失败，请重试后再开始提问", true);
            stage(stageUpdater, "部分章节理解失败，请重试后再开始提问");
            return result(record, ordered, null);
        }

        stage(stageUpdater, "正在汇总论文全局画像…");
        record.setStageText("正在汇总论文全局画像…");
        memoryMapper.updateById(record);
        try {
            PaperMemoryModelService.ProfileGeneration generated = modelService.profile(
                    state.structure(), ordered, chunks.size(), failed);
            String status = failed == 0 ? STATUS_READY : STATUS_PARTIAL;
            String errorCode = failed == 0 ? null : "CHUNK_SUMMARY_PARTIAL";
            String stageText = failed == 0 ? "论文记忆已就绪"
                    : "论文记忆部分就绪，可重试失败分块";
            finish(record, status, ordered, generated.profile(),
                    promptTokens(ordered) + generated.promptTokens(),
                    completionTokens(ordered) + generated.completionTokens(),
                    errorCode, stageText, true);
            stage(stageUpdater, stageText);
            return result(record, ordered, generated.profile());
        } catch (RuntimeException exception) {
            log.warn("paper_profile_generation_failed paperId={} memoryId={} errorType={}",
                    paperId, record.getId(), exception.getClass().getSimpleName());
            int extraPromptTokens = usagePromptTokens(exception);
            int extraCompletionTokens = usageCompletionTokens(exception);
            finish(record, STATUS_PARTIAL, ordered, null,
                    promptTokens(ordered) + extraPromptTokens,
                    completionTokens(ordered) + extraCompletionTokens,
                    "PROFILE_GENERATION_FAILED", "分块摘要已就绪，全局画像生成失败", true);
            stage(stageUpdater, "分块摘要已就绪，全局画像生成失败");
            return result(record, ordered, null);
        }
    }

    private PaperUnderstandingResult understandWholePaper(
            PaperMemoryState state,
            PaperMemoryRecord record,
            PaperMemoryChunk chunk,
            Consumer<String> stageUpdater) {
        stage(stageUpdater, "正在理解论文全文…");
        record.setStageText("正在理解论文全文…");
        memoryMapper.updateById(record);
        try {
            PaperMemoryModelService.WholePaperGeneration generated =
                    modelService.understandWhole(state.structure(), chunk);
            List<PaperChunkSummary> summaries = List.of(generated.summary());
            finish(record, STATUS_READY, summaries, generated.profile(),
                    promptTokens(summaries), completionTokens(summaries),
                    null, "论文记忆已就绪", true);
            stage(stageUpdater, "论文记忆已就绪");
            return result(record, summaries, generated.profile());
        } catch (RuntimeException exception) {
            log.warn("paper_whole_understanding_failed paperId={} memoryId={} errorType={}",
                    state.paperId(), record.getId(), exception.getClass().getSimpleName());
            PaperChunkSummary failed = PaperChunkSummary.failed(
                    chunk, "WHOLE_PAPER_GENERATION_FAILED",
                    usagePromptTokens(exception), usageCompletionTokens(exception),
                    usageFinishReason(exception));
            finish(record, STATUS_FAILED, List.of(failed), null,
                    failed.promptTokens(), failed.completionTokens(),
                    "WHOLE_PAPER_GENERATION_FAILED",
                    "论文全文理解失败，请重试后再开始提问", true);
            stage(stageUpdater, "论文全文理解失败，请重试后再开始提问");
            return result(record, List.of(failed), null);
        }
    }

    private PaperChunkSummary summarizeSafely(PaperMemoryChunk chunk) {
        try {
            return modelService.summarize(chunk);
        } catch (RuntimeException exception) {
            log.warn("paper_chunk_summary_failed chunkId={} errorType={}",
                    chunk.id(), exception.getClass().getSimpleName());
            return PaperChunkSummary.failed(
                    chunk, "MODEL_SUMMARY_FAILED",
                    usagePromptTokens(exception), usageCompletionTokens(exception),
                    usageFinishReason(exception));
        }
    }

    private int usagePromptTokens(RuntimeException exception) {
        return exception instanceof PaperMemoryGenerationException generation
                ? generation.promptTokens() : 0;
    }

    private int usageCompletionTokens(RuntimeException exception) {
        return exception instanceof PaperMemoryGenerationException generation
                ? generation.completionTokens() : 0;
    }

    private String usageFinishReason(RuntimeException exception) {
        return exception instanceof PaperMemoryGenerationException generation
                ? generation.finishReason() : "";
    }

    private List<PaperChunkSummary> compatibleSummaries(PaperMemoryRecord record,
                                                        List<PaperMemoryChunk> chunks,
                                                        boolean forceRefresh) {
        if (forceRefresh || !PIPELINE_VERSION.equals(record.getUnderstandingVersion())
                || record.getChunkSummariesJson() == null || record.getChunkSummariesJson().isBlank()) {
            return List.of();
        }
        try {
            Map<String, PaperMemoryChunk> current = new LinkedHashMap<>();
            chunks.forEach(chunk -> current.put(chunk.id(), chunk));
            return objectMapper.readValue(record.getChunkSummariesJson(), SUMMARY_LIST).stream()
                    .filter(PaperChunkSummary::ready)
                    .filter(summary -> {
                        PaperMemoryChunk chunk = current.get(summary.chunkId());
                        return chunk != null && chunk.sourceFingerprint().equals(summary.sourceFingerprint());
                    })
                    .sorted(Comparator.comparingInt(PaperChunkSummary::ordinal))
                    .toList();
        } catch (Exception exception) {
            log.warn("paper_chunk_summary_cache_invalid memoryId={}", record.getId());
            return List.of();
        }
    }

    private PaperGlobalProfile compatibleProfile(PaperMemoryRecord record, boolean forceRefresh) {
        if (forceRefresh || !PIPELINE_VERSION.equals(record.getUnderstandingVersion())
                || record.getProfileJson() == null || record.getProfileJson().isBlank()) return null;
        try {
            PaperGlobalProfile profile = objectMapper.readValue(
                    record.getProfileJson(), PaperGlobalProfile.class);
            return PaperGlobalProfile.SCHEMA_VERSION.equals(profile.schemaVersion()) ? profile : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private void initialize(PaperMemoryRecord record,
                            int totalChunks,
                            java.util.Collection<PaperChunkSummary> existing) {
        List<PaperChunkSummary> ordered = ordered(existing);
        record.setStatus(STATUS_UNDERSTANDING);
        record.setUnderstandingVersion(PIPELINE_VERSION);
        record.setStageText(progressText(readyCount(ordered), totalChunks));
        record.setTotalChunks(totalChunks);
        record.setCompletedChunks(readyCount(ordered));
        record.setFailedChunks(0);
        record.setPromptTokens(promptTokens(ordered));
        record.setCompletionTokens(completionTokens(ordered));
        record.setChunkSummariesJson(write(ordered));
        record.setProfileJson(null);
        record.setLastErrorCode(null);
        record.setUnderstandingStartedAt(LocalDateTime.now());
        record.setUnderstandingCompletedAt(null);
        record.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(record);
    }

    private void checkpoint(PaperMemoryRecord record,
                            int totalChunks,
                            List<PaperChunkSummary> summaries) {
        int completed = readyCount(summaries);
        int failed = failedCount(summaries);
        record.setStatus(STATUS_UNDERSTANDING);
        record.setStageText(progressText(completed + failed, totalChunks));
        record.setTotalChunks(totalChunks);
        record.setCompletedChunks(completed);
        record.setFailedChunks(failed);
        record.setPromptTokens(promptTokens(summaries));
        record.setCompletionTokens(completionTokens(summaries));
        record.setChunkSummariesJson(write(summaries));
        record.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(record);
    }

    private void finish(PaperMemoryRecord record,
                        String status,
                        List<PaperChunkSummary> summaries,
                        PaperGlobalProfile profile,
                        int promptTokens,
                        int completionTokens,
                        String errorCode,
                        String stageText,
                        boolean incrementRevision) {
        record.setStatus(status);
        record.setUnderstandingVersion(PIPELINE_VERSION);
        record.setStageText(stageText);
        record.setTotalChunks(Math.max(record.getTotalChunks() == null ? 0 : record.getTotalChunks(), summaries.size()));
        record.setCompletedChunks(readyCount(summaries));
        record.setFailedChunks(failedCount(summaries));
        record.setPromptTokens(Math.max(0, promptTokens));
        record.setCompletionTokens(Math.max(0, completionTokens));
        record.setChunkSummariesJson(write(summaries));
        record.setProfileJson(profile == null ? null : write(profile));
        record.setLastErrorCode(errorCode);
        record.setUnderstandingCompletedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        if (incrementRevision) {
            record.setRevision((record.getRevision() == null ? 0 : record.getRevision()) + 1);
        }
        memoryMapper.updateById(record);
    }

    private PaperUnderstandingResult result(PaperMemoryRecord record,
                                            List<PaperChunkSummary> summaries,
                                            PaperGlobalProfile profile) {
        return new PaperUnderstandingResult(
                record.getId(), record.getPaperId(), record.getStatus(),
                count(record.getTotalChunks()), count(record.getCompletedChunks()),
                count(record.getFailedChunks()), count(record.getPromptTokens()),
                count(record.getCompletionTokens()), summaries, profile);
    }

    private List<PaperChunkSummary> ordered(java.util.Collection<PaperChunkSummary> summaries) {
        return summaries.stream()
                .sorted(Comparator.comparingInt(PaperChunkSummary::ordinal))
                .toList();
    }

    private int readyCount(List<PaperChunkSummary> summaries) {
        return (int) summaries.stream().filter(PaperChunkSummary::ready).count();
    }

    private int failedCount(List<PaperChunkSummary> summaries) {
        return (int) summaries.stream().filter(summary -> !summary.ready()).count();
    }

    private int promptTokens(List<PaperChunkSummary> summaries) {
        return summaries.stream().mapToInt(PaperChunkSummary::promptTokens).sum();
    }

    private int completionTokens(List<PaperChunkSummary> summaries) {
        return summaries.stream().mapToInt(PaperChunkSummary::completionTokens).sum();
    }

    private String progressText(int processed, int total) {
        return "正在理解论文内容 " + Math.min(processed, total) + "/" + total + "…";
    }

    private void stage(Consumer<String> updater, String text) {
        if (updater != null) updater.accept(text);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("论文语义记忆无法序列化", exception);
        }
    }

    private int count(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
