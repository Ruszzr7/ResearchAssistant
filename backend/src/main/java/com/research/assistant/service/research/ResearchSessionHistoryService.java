package com.research.assistant.service.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperWorkbenchRunRecord;
import com.research.assistant.entity.ResearchSession;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.mapper.ResearchSessionPaperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HexFormat;

@Service
public class ResearchSessionHistoryService {
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() { };

    private final ResearchSessionMapper sessionMapper;
    private final ResearchSessionPaperMapper sessionPaperMapper;
    private final PaperWorkbenchRunMapper runMapper;
    private final PaperMapper paperMapper;
    private final ObjectMapper objectMapper;

    public ResearchSessionHistoryService(ResearchSessionMapper sessionMapper,
                                         ResearchSessionPaperMapper sessionPaperMapper,
                                         PaperWorkbenchRunMapper runMapper,
                                         PaperMapper paperMapper,
                                         ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.sessionPaperMapper = sessionPaperMapper;
        this.runMapper = runMapper;
        this.paperMapper = paperMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int backfillRecent() {
        int linked = 0;
        for (PaperWorkbenchRunRecord run : runMapper.selectUnlinked(200)) {
            // History is user-visible conversation state. A planned/running/failed audit run has
            // no assistant message to restore and must never create an empty conversation card.
            if (!isCompletedResult(run)) continue;
            List<Long> paperIds = existingPaperIds(readPaperIds(run.getPaperIdsJson()));
            if (paperIds.isEmpty()) continue;
            String key = historyKey(paperIds);
            ResearchSession session = sessionMapper.selectBySessionKey(key);
            if (session == null) session = createHistorySession(key, paperIds, run);
            run.setResearchSessionId(session.getId());
            runMapper.updateById(run);
            linked++;
        }
        return linked;
    }

    private boolean isCompletedResult(PaperWorkbenchRunRecord run) {
        return run != null
                && "COMPLETED".equals(run.getStatus())
                && run.getResultJson() != null
                && !run.getResultJson().isBlank();
    }

    private List<Long> existingPaperIds(List<Long> paperIds) {
        if (paperIds.isEmpty()) return paperIds;
        var existing = paperMapper.selectBatchIds(paperIds).stream()
                .map(Paper::getId)
                .collect(java.util.stream.Collectors.toSet());
        return paperIds.stream().filter(existing::contains).toList();
    }

    private ResearchSession createHistorySession(String key, List<Long> paperIds, PaperWorkbenchRunRecord run) {
        Paper primary = paperMapper.selectById(paperIds.get(0));
        if (primary == null) throw new IllegalArgumentException("历史运行引用的论文不存在");
        LocalDateTime activity = run.getUpdatedAt() == null ? LocalDateTime.now() : run.getUpdatedAt();
        ResearchSession session = new ResearchSession();
        session.setSessionKey(key);
        session.setTitle(paperIds.size() == 1
                ? safeTitle(primary) : safeTitle(primary) + " 等 " + paperIds.size() + " 篇");
        session.setSessionType(paperIds.size() > 1 ? "MULTI" : "SINGLE");
        session.setPrimaryPaperId(primary.getId());
        session.setLastPage(primary.getCurrentPage() == null || primary.getCurrentPage() < 1 ? 1 : primary.getCurrentPage());
        session.setMode(run.getWorkflow() == null ? "analysis" : run.getWorkflow().toLowerCase());
        session.setOutputLanguage("ZH");
        session.setArchived(false);
        session.setLastActivityAt(activity);
        session.setCreatedAt(activity);
        session.setUpdatedAt(activity);
        sessionMapper.insert(session);
        for (int index = 0; index < paperIds.size(); index++) {
            if (paperMapper.selectById(paperIds.get(index)) != null) {
                sessionPaperMapper.insertLink(session.getId(), paperIds.get(index), index);
            }
        }
        return session;
    }

    private List<Long> readPaperIds(String json) {
        try {
            List<Long> ids = objectMapper.readValue(json, LONG_LIST);
            return new ArrayList<>(new LinkedHashSet<>(ids.stream()
                    .filter(id -> id != null && id > 0)
                    .toList()));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String historyKey(List<Long> paperIds) {
        try {
            List<Long> sorted = paperIds.stream().sorted(Comparator.naturalOrder()).toList();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sorted.toString().getBytes(StandardCharsets.UTF_8));
            return "history-" + HexFormat.of().formatHex(digest, 0, 20);
        } catch (Exception error) {
            throw new IllegalStateException("无法生成历史研究档案标识", error);
        }
    }

    private String safeTitle(Paper paper) {
        return paper.getTitle() == null || paper.getTitle().isBlank() ? "论文分析" : paper.getTitle();
    }
}
