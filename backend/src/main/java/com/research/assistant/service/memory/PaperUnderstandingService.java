package com.research.assistant.service.memory;

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
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds one complete, versioned understanding of a paper.
 *
 * <p>The service deliberately has one semantic model call per paper. Page and
 * block markers are only transport metadata; there is no chunk summarisation,
 * global aggregation, retry repair, or long-paper map/reduce path.</p>
 */
@Service
public class PaperUnderstandingService {

    public static final String PIPELINE_VERSION = "paper-understanding-v7-visual-fallback";
    public static final String STATUS_UNDERSTANDING = "UNDERSTANDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    private static final Logger log = LoggerFactory.getLogger(PaperUnderstandingService.class);

    private final PaperMemoryService memoryService;
    private final PaperMemoryMapper memoryMapper;
    private final PaperLayoutArtifactService artifactService;
    private final PaperMemoryChunker chunker;
    private final PaperMemoryModelService modelService;
    private final ObjectMapper objectMapper;
    private final PaperProfileQualityValidator qualityValidator;

    /** Constructor retained for focused tests and source compatibility. */
    public PaperUnderstandingService(PaperMemoryService memoryService,
                                     PaperMemoryMapper memoryMapper,
                                     PaperLayoutArtifactService artifactService,
                                     PaperMemoryChunker chunker,
                                     PaperMemoryModelService modelService,
                                     ObjectMapper objectMapper,
                                     @Qualifier("paperMemoryExecutor") ThreadPoolTaskExecutor ignoredExecutor) {
        this(memoryService, memoryMapper, artifactService, chunker, modelService,
                objectMapper, ignoredExecutor, new PaperProfileQualityValidator());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperUnderstandingService(PaperMemoryService memoryService,
                                     PaperMemoryMapper memoryMapper,
                                     PaperLayoutArtifactService artifactService,
                                     PaperMemoryChunker chunker,
                                     PaperMemoryModelService modelService,
                                     ObjectMapper objectMapper,
                                     @Qualifier("paperMemoryExecutor") ThreadPoolTaskExecutor ignoredExecutor,
                                     PaperProfileQualityValidator qualityValidator) {
        this.memoryService = memoryService;
        this.memoryMapper = memoryMapper;
        this.artifactService = artifactService;
        this.chunker = chunker;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.qualityValidator = qualityValidator;
    }

    public PaperUnderstandingResult understand(Long paperId,
                                                boolean forceRefresh,
                                                Consumer<String> stageUpdater) {
        PaperMemoryState state = memoryService.ensureStructure(paperId, false);
        PaperMemoryRecord record = memoryMapper.selectVersion(
                paperId, state.documentHash(), state.layoutParserVersion(), state.schemaVersion());
        if (record == null) throw new IllegalStateException("论文结构记忆未持久化");
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
        PaperGlobalProfile cached = compatibleProfile(record, forceRefresh);
        if (!forceRefresh && STATUS_READY.equals(record.getStatus())
                && profileQualityReady(record) && cached != null) {
            return result(record, List.of(), cached, readRecoveries(record));
        }

        PaperMemoryChunk wholePaper = chunker.wholePaper(state.structure(), artifact);
        if (wholePaper.blockIds().isEmpty() || wholePaper.text().isBlank()) {
            beginAttempt(record);
            finish(record, STATUS_FAILED, List.of(), null, 0, 0,
                    List.of(), "NO_PAPER_CONTENT", "未找到可理解的论文正文");
            return result(record, List.of(), null, List.of());
        }

        beginAttempt(record);
        initialize(record);
        stage(stageUpdater, "正在一次性理解论文全文…");
        record.setStageText("正在一次性理解论文全文…");
        memoryMapper.updateById(record);

        try {
            PaperMemoryModelService.WholePaperGeneration generated =
                    modelService.understandWhole(state.structure(), artifact);
            PaperGlobalProfile profile = generated.profile();
            PaperProfileQualityReport quality = qualityValidator.validate(state.structure(), profile);
            record.setProfileQualityJson(write(quality));
            PaperChunkSummary summary = generated.summary();
            String status = quality.ready() ? STATUS_READY
                    : quality.usable() ? STATUS_PARTIAL : STATUS_FAILED;
            String errorCode = quality.ready() ? null
                    : quality.usable() ? "PROFILE_QUALITY_PARTIAL" : "PROFILE_QUALITY_FAILED";
            String stageText = quality.ready() ? "论文记忆已就绪"
                    : quality.usable() ? "论文理解部分完成，请重试" : "论文理解质量未达要求，请重试";
            long correctedRegions = generated.recoveries().stream()
                    .filter(PaperLayoutRecovery::corrected).count();
            long unresolvedRegions = generated.recoveries().size() - correctedRegions;
            log.info("paper_understanding_quality paperId={} ready={} usable={} issues={} "
                            + "correctedRegions={} unresolvedRegions={}",
                    paperId, quality.ready(), quality.usable(), quality.issues(),
                    correctedRegions, unresolvedRegions);
            finish(record, status, List.of(summary), profile,
                    summary.promptTokens(), summary.completionTokens(), generated.recoveries(),
                    errorCode, stageText);
            stage(stageUpdater, stageText);
            return result(record, List.of(summary), profile, generated.recoveries());
        } catch (RuntimeException exception) {
            Throwable root = exception;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            log.warn("paper_whole_understanding_failed paperId={} memoryId={} errorType={} rootType={} message={}",
                    paperId, record.getId(), exception.getClass().getSimpleName(),
                    root.getClass().getSimpleName(), root.getMessage());
            PaperChunkSummary failed = PaperChunkSummary.failed(
                    wholePaper, "WHOLE_PAPER_GENERATION_FAILED",
                    usagePromptTokens(exception), usageCompletionTokens(exception),
                    usageFinishReason(exception));
            finish(record, STATUS_FAILED, List.of(failed), null,
                    failed.promptTokens(), failed.completionTokens(),
                    List.of(), "WHOLE_PAPER_GENERATION_FAILED", "论文全文理解失败，请重试");
            stage(stageUpdater, "论文全文理解失败，请重试");
            return result(record, List.of(failed), null, List.of());
        }
    }

    private PaperGlobalProfile compatibleProfile(PaperMemoryRecord record, boolean forceRefresh) {
        if (forceRefresh || !PIPELINE_VERSION.equals(record.getUnderstandingVersion())
                || record.getProfileJson() == null || record.getProfileJson().isBlank()) {
            return null;
        }
        try {
            PaperGlobalProfile profile = objectMapper.readValue(
                    record.getProfileJson(), PaperGlobalProfile.class);
            return PaperGlobalProfile.SCHEMA_VERSION.equals(profile.schemaVersion()) ? profile : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean profileQualityReady(PaperMemoryRecord record) {
        if (record.getProfileQualityJson() == null || record.getProfileQualityJson().isBlank()) return false;
        try {
            return objectMapper.readTree(record.getProfileQualityJson()).path("ready").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void initialize(PaperMemoryRecord record) {
        record.setStatus(STATUS_UNDERSTANDING);
        record.setUnderstandingVersion(PIPELINE_VERSION);
        record.setStageText("正在一次性理解论文全文…");
        record.setTotalChunks(1);
        record.setCompletedChunks(0);
        record.setFailedChunks(0);
        record.setPromptTokens(0);
        record.setCompletionTokens(0);
        record.setChunkSummariesJson(null);
        record.setProfileJson(null);
        record.setLayoutRecoveryJson(null);
        record.setProfileQualityJson(null);
        record.setLastErrorCode(null);
        record.setUnderstandingStartedAt(LocalDateTime.now());
        record.setUnderstandingCompletedAt(null);
        record.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(record);
    }

    private void beginAttempt(PaperMemoryRecord record) {
        int current = record.getUnderstandingAttemptCount() == null
                ? 0 : Math.max(0, record.getUnderstandingAttemptCount());
        record.setUnderstandingAttemptCount(current + 1);
        record.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(record);
    }

    private void finish(PaperMemoryRecord record,
                        String status,
                        List<PaperChunkSummary> summaries,
                        PaperGlobalProfile profile,
                        int promptTokens,
                        int completionTokens,
                        List<PaperLayoutRecovery> recoveries,
                        String errorCode,
                        String stageText) {
        record.setStatus(status);
        record.setUnderstandingVersion(PIPELINE_VERSION);
        record.setStageText(stageText);
        record.setTotalChunks(1);
        record.setCompletedChunks(summaries.stream().anyMatch(PaperChunkSummary::ready) ? 1 : 0);
        record.setFailedChunks(summaries.stream().anyMatch(summary -> !summary.ready()) ? 1 : 0);
        record.setPromptTokens(Math.max(0, promptTokens));
        record.setCompletionTokens(Math.max(0, completionTokens));
        record.setChunkSummariesJson(write(summaries));
        record.setProfileJson(profile == null ? null : write(profile));
        record.setLayoutRecoveryJson(recoveries == null || recoveries.isEmpty() ? null : write(recoveries));
        record.setLastErrorCode(errorCode);
        record.setUnderstandingCompletedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        record.setRevision((record.getRevision() == null ? 0 : record.getRevision()) + 1);
        memoryMapper.updateById(record);
    }

    private PaperUnderstandingResult result(PaperMemoryRecord record,
                                            List<PaperChunkSummary> summaries,
                                            PaperGlobalProfile profile,
                                            List<PaperLayoutRecovery> recoveries) {
        return new PaperUnderstandingResult(
                record.getId(), record.getPaperId(), record.getStatus(),
                count(record.getTotalChunks()), count(record.getCompletedChunks()),
                count(record.getFailedChunks()), count(record.getPromptTokens()),
                count(record.getCompletionTokens()), summaries, profile, recoveries);
    }

    private List<PaperLayoutRecovery> readRecoveries(PaperMemoryRecord record) {
        if (record.getLayoutRecoveryJson() == null || record.getLayoutRecoveryJson().isBlank()) return List.of();
        try {
            return objectMapper.readValue(record.getLayoutRecoveryJson(), objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, PaperLayoutRecovery.class));
        } catch (Exception ignored) {
            return List.of();
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
