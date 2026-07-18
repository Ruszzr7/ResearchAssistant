package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperMemoryMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Read-only paper-memory status used by the non-blocking reading UI. */
@Service
public class PaperMemoryQueryService {

    private final PaperMapper paperMapper;
    private final PaperMemoryMapper memoryMapper;
    private final ObjectMapper objectMapper;

    public PaperMemoryQueryService(PaperMapper paperMapper,
                                   PaperMemoryMapper memoryMapper,
                                   ObjectMapper objectMapper) {
        this.paperMapper = paperMapper;
        this.memoryMapper = memoryMapper;
        this.objectMapper = objectMapper;
    }

    public PaperMemoryStatusView status(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) throw new IllegalArgumentException("论文不存在");
        PaperMemoryRecord record = memoryMapper.selectLatest(paperId);
        if (record == null) {
            boolean hasPdf = paper.getPdfPath() != null && !paper.getPdfPath().isBlank();
            return new PaperMemoryStatusView(
                    paperId, null, 0, "NOT_STARTED",
                    hasPdf ? "等待建立论文记忆" : "尚未上传 PDF",
                    0, 0, 0, 0, 0, 0,
                    false, false, hasPdf, false, "", null, null);
        }
        int total = count(record.getTotalChunks());
        int completed = count(record.getCompletedChunks());
        int failed = count(record.getFailedChunks());
        String status = safe(record.getStatus(), PaperMemoryService.STATUS_STRUCTURED);
        boolean profileReady = record.getProfileJson() != null && !record.getProfileJson().isBlank();
        PaperGlobalProfile profile = profileReady ? readProfile(record.getProfileJson()) : null;
        profileReady = profile != null;
        int progress = switch (status) {
            case PaperUnderstandingService.STATUS_READY -> 100;
            case PaperMemoryService.STATUS_STRUCTURED -> 5;
            default -> total == 0 ? 0 : Math.min(100,
                    (int) Math.round((completed + failed) * 100.0 / total));
        };
        boolean active = PaperUnderstandingService.STATUS_UNDERSTANDING.equals(status);
        boolean retry = PaperUnderstandingService.STATUS_PARTIAL.equals(status)
                || PaperUnderstandingService.STATUS_FAILED.equals(status);
        return new PaperMemoryStatusView(
                paperId, record.getId(), count(record.getRevision()), status,
                safe(record.getStageText(), defaultStage(status)), progress,
                total, completed, failed,
                count(record.getPromptTokens()), count(record.getCompletionTokens()),
                true, profileReady,
                !active && !PaperUnderstandingService.STATUS_READY.equals(status),
                retry, safe(record.getLastErrorCode(), ""), profile,
                toInstant(record.getUpdatedAt()));
    }

    private PaperGlobalProfile readProfile(String json) {
        try {
            PaperGlobalProfile profile = objectMapper.readValue(json, PaperGlobalProfile.class);
            return PaperGlobalProfile.SCHEMA_VERSION.equals(profile.schemaVersion()) ? profile : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String defaultStage(String status) {
        return switch (status) {
            case PaperUnderstandingService.STATUS_UNDERSTANDING -> "正在理解论文内容…";
            case PaperUnderstandingService.STATUS_READY -> "论文记忆已就绪";
            case PaperUnderstandingService.STATUS_PARTIAL -> "论文记忆部分就绪";
            case PaperUnderstandingService.STATUS_FAILED -> "论文记忆构建失败";
            default -> "PDF 结构已就绪";
        };
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int count(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
