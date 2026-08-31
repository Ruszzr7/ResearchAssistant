package com.research.assistant.service.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.research.assistant.dto.research.*;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.entity.ResearchSession;
import com.research.assistant.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ResearchSessionService {

    private final ResearchSessionMapper sessionMapper;
    private final ResearchSessionPaperMapper sessionPaperMapper;
    private final ResearchMessageMapper messageMapper;
    private final PaperMapper paperMapper;
    private final AgentTurnMapper agentTurnMapper;

    public ResearchSessionService(ResearchSessionMapper sessionMapper,
                                  ResearchSessionPaperMapper sessionPaperMapper,
                                  ResearchMessageMapper messageMapper,
                                  PaperMapper paperMapper,
                                  AgentTurnMapper agentTurnMapper) {
        this.sessionMapper = sessionMapper;
        this.sessionPaperMapper = sessionPaperMapper;
        this.messageMapper = messageMapper;
        this.paperMapper = paperMapper;
        this.agentTurnMapper = agentTurnMapper;
    }

    public List<ResearchSessionSummary> list(boolean archived, String keyword, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        String safeKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return sessionMapper.selectRecent(archived, safeKeyword, bounded).stream()
                .map(this::toSummary)
                .toList();
    }

    public ResearchSessionDetail get(long sessionId) {
        ResearchSession session = requireSession(sessionId);
        List<ResearchMessageView> messages = messageMapper.selectBySessionId(sessionId).stream()
                .map(this::toMessageView)
                .toList();
        return new ResearchSessionDetail(toSummary(session), messages);
    }

    @Transactional
    public ResearchSessionSummary create(ResearchSessionCreateRequest request) {
        List<Long> paperIds = normalizePaperIds(request.paperIds());
        Long primaryPaperId = request.primaryPaperId() == null ? paperIds.get(0) : request.primaryPaperId();
        if (!paperIds.contains(primaryPaperId)) throw new IllegalArgumentException("主论文必须包含在研究论文中");
        List<Paper> papers = requirePapers(paperIds);
        LocalDateTime now = LocalDateTime.now();

        ResearchSession session = new ResearchSession();
        session.setSessionKey(UUID.randomUUID().toString());
        session.setTitle(normalizeTitle(request.title(), papers, paperIds.size()));
        session.setSessionType(paperIds.size() > 1 ? "MULTI" : "SINGLE");
        session.setPrimaryPaperId(primaryPaperId);
        session.setLastPage(request.lastPage() == null ? 1 : request.lastPage());
        session.setMode(normalizeMode(request.mode()));
        session.setOutputLanguage(normalizeLanguage(request.outputLanguage()));
        session.setArchived(false);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        replacePapers(session.getId(), paperIds);
        return toSummary(session);
    }

    @Transactional
    public ResearchSessionSummary update(long sessionId, ResearchSessionUpdateRequest request) {
        ResearchSession session = requireSession(sessionId);
        if (request.title() != null) {
            String title = request.title().trim();
            if (title.isBlank()) throw new IllegalArgumentException("研究档案标题不能为空");
            session.setTitle(title);
        }
        if (request.lastPage() != null) session.setLastPage(request.lastPage());
        if (request.mode() != null) session.setMode(normalizeMode(request.mode()));
        if (request.outputLanguage() != null) session.setOutputLanguage(normalizeLanguage(request.outputLanguage()));
        if (request.archived() != null) session.setArchived(request.archived());
        if (request.paperIds() != null) {
            List<Long> paperIds = normalizePaperIds(request.paperIds());
            requirePapers(paperIds);
            if (!paperIds.contains(session.getPrimaryPaperId())) {
                paperIds = new ArrayList<>(paperIds);
                paperIds.add(0, session.getPrimaryPaperId());
                paperIds = normalizePaperIds(paperIds);
            }
            session.setSessionType(paperIds.size() > 1 ? "MULTI" : "SINGLE");
            replacePapers(sessionId, paperIds);
        }
        touch(session);
        sessionMapper.updateById(session);
        return toSummary(session);
    }

    @Transactional
    public void delete(long sessionId) {
        requireSession(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    private void replacePapers(long sessionId, List<Long> paperIds) {
        sessionPaperMapper.deleteBySessionId(sessionId);
        for (int index = 0; index < paperIds.size(); index++) {
            sessionPaperMapper.insertLink(sessionId, paperIds.get(index), index);
        }
    }

    private ResearchSessionSummary toSummary(ResearchSession session) {
        ResearchSessionSummary summary = new ResearchSessionSummary();
        summary.setId(session.getId());
        summary.setTitle(session.getTitle());
        summary.setSessionType(session.getSessionType());
        summary.setPrimaryPaperId(session.getPrimaryPaperId());
        summary.setLastPage(session.getLastPage());
        summary.setMode(session.getMode());
        summary.setOutputLanguage(session.getOutputLanguage());
        summary.setArchived(session.getArchived());
        summary.setMessageCount(messageMapper.countBySessionId(session.getId()));
        summary.setRunCount(agentTurnMapper.countBySessionId(session.getId()));
        summary.setPapers(sessionPaperMapper.selectPapers(session.getId()));
        summary.setLastActivityAt(session.getLastActivityAt());
        summary.setCreatedAt(session.getCreatedAt());
        summary.setUpdatedAt(session.getUpdatedAt());
        return summary;
    }

    private ResearchMessageView toMessageView(ResearchMessage message) {
        ResearchMessageView view = new ResearchMessageView();
        view.setId(message.getId());
        view.setMessageKey(message.getMessageKey());
        view.setRole(message.getRole());
        view.setContent(message.getContent());
        view.setRunId(message.getRunId());
        view.setSelectionAnchor(readNullable(message.getSelectionAnchorJson()));
        view.setEvidence(readNullable(message.getEvidenceJson()));
        view.setCreatedAt(message.getCreatedAt());
        return view;
    }

    private ResearchSession requireSession(long sessionId) {
        ResearchSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("研究档案不存在");
        return session;
    }

    private List<Long> normalizePaperIds(List<Long> paperIds) {
        if (paperIds == null) throw new IllegalArgumentException("研究论文不能为空");
        List<Long> normalized = new ArrayList<>(new LinkedHashSet<>(paperIds.stream()
                .filter(id -> id != null && id > 0)
                .toList()));
        if (normalized.isEmpty() || normalized.size() > 8) {
            throw new IllegalArgumentException("研究档案需要 1 到 8 篇论文");
        }
        return normalized;
    }

    private List<Paper> requirePapers(List<Long> paperIds) {
        List<Paper> papers = paperMapper.selectBatchIds(paperIds);
        if (papers.size() != paperIds.size()) throw new IllegalArgumentException("研究档案包含不存在的论文");
        return papers;
    }

    private String normalizeTitle(String requested, List<Paper> papers, int paperCount) {
        if (requested != null && !requested.isBlank()) return requested.trim();
        String base = papers.stream().filter(paper -> paper.getTitle() != null && !paper.getTitle().isBlank())
                .map(Paper::getTitle).findFirst().orElse("论文分析");
        return paperCount > 1 ? base + " 等 " + paperCount + " 篇" : base;
    }

    private String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "analysis" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeLanguage(String language) {
        String value = language == null ? "ZH" : language.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("ZH") && !value.equals("EN")) throw new IllegalArgumentException("输出语言仅支持 ZH 或 EN");
        return value;
    }

    private void touch(ResearchSession session) {
        LocalDateTime now = LocalDateTime.now();
        session.setLastActivityAt(now);
        session.setUpdatedAt(now);
    }

    private JsonNode readNullable(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JsonSupport.MAPPER.readTree(json); }
        catch (Exception ignored) { return null; }
    }

    private static final class JsonSupport {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
