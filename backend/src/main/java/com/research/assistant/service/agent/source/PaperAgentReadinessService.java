package com.research.assistant.service.agent.source;

import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.memory.PaperUnderstandingService;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class PaperAgentReadinessService {

    public static final int FALLBACK_AFTER_ATTEMPTS = 3;

    private final PaperMapper paperMapper;
    private final PaperMemoryMapper memoryMapper;
    private final PaperLayoutArtifactService artifactService;
    private final AiCapabilityService capabilityService;

    public PaperAgentReadinessService(PaperMapper paperMapper,
                                      PaperMemoryMapper memoryMapper,
                                      PaperLayoutArtifactService artifactService) {
        this(paperMapper, memoryMapper, artifactService, null);
    }

    @Autowired
    public PaperAgentReadinessService(PaperMapper paperMapper,
                                      PaperMemoryMapper memoryMapper,
                                      PaperLayoutArtifactService artifactService,
                                      AiCapabilityService capabilityService) {
        this.paperMapper = paperMapper;
        this.memoryMapper = memoryMapper;
        this.artifactService = artifactService;
        this.capabilityService = capabilityService;
    }

    public PaperAgentReadinessView status(long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) throw new IllegalArgumentException("论文不存在");
        boolean fileReady = paper.getPdfPath() != null && !paper.getPdfPath().isBlank();
        PaperMemoryRecord memory = memoryMapper.selectLatest(paperId);
        PaperLayoutArtifact artifact;
        try {
            artifact = artifactService.latestArtifact(paperId);
        } catch (IllegalStateException invalidCachedArtifact) {
            // A stale layout payload must not make the manual understanding entry disappear.
            // The understanding pipeline will rebuild an invalid cache through ensureArtifact().
            artifact = null;
        }
        boolean localReady = artifact != null && memory != null
                && artifact.documentHash().equals(memory.getDocumentHash())
                && memory.getStructureJson() != null && !memory.getStructureJson().isBlank();
        int attempts = memory == null || memory.getUnderstandingAttemptCount() == null
                ? 0 : Math.max(0, memory.getUnderstandingAttemptCount());
        String memoryStatus = memory == null ? "NOT_STARTED" : memory.getStatus();
        boolean profileReady = memory != null
                && PaperUnderstandingService.STATUS_READY.equals(memoryStatus)
                && memory.getProfileJson() != null && !memory.getProfileJson().isBlank()
                && qualityReady(memory.getProfileQualityJson());
        boolean failed = PaperUnderstandingService.STATUS_PARTIAL.equals(memoryStatus)
                || PaperUnderstandingService.STATUS_FAILED.equals(memoryStatus);
        boolean fallback = localReady && failed
                && (qualityUsable(memory == null ? null : memory.getProfileQualityJson())
                || attempts >= FALLBACK_AFTER_ATTEMPTS);
        boolean active = PaperUnderstandingService.STATUS_UNDERSTANDING.equals(memoryStatus);

        String status;
        String text;
        if (profileReady) {
            status = "PROFILE_READY";
            text = "论文理解已完成";
        } else if (fallback) {
            status = "FALLBACK_READY";
            text = "论文理解部分完成，将按原文回答";
        } else if (active) {
            status = "UNDERSTANDING";
            text = "正在理解论文";
        } else if (failed) {
            status = "RETRY_REQUIRED";
            text = "论文理解未完成，请重试";
        } else if (localReady) {
            status = "SOURCE_READY";
            text = "论文原文已就绪，等待理解";
        } else if (fileReady) {
            status = "NOT_STARTED";
            text = "等待开始论文理解";
        } else {
            status = "FILE_MISSING";
            text = "论文 PDF 不可用";
        }
        boolean visualReady = capabilityService != null && capabilityService.documentImageReady();
        return new PaperAgentReadinessView(paperId, status, text, fileReady, localReady,
                localReady, profileReady, visualReady, fallback, profileReady || fallback, attempts);
    }

    private static boolean qualityReady(String qualityJson) {
        if (qualityJson == null || qualityJson.isBlank()) return false;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(qualityJson).path("ready").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean qualityUsable(String qualityJson) {
        if (qualityJson == null || qualityJson.isBlank()) return false;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(qualityJson).path("usable").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
