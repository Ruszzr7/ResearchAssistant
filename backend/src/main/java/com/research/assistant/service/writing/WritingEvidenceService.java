package com.research.assistant.service.writing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.assistant.dto.WritingClaimDto;
import com.research.assistant.dto.WritingClaimRequest;
import com.research.assistant.dto.WritingEvidenceDto;
import com.research.assistant.dto.WritingEvidenceRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.ResearchSession;
import com.research.assistant.entity.WritingClaim;
import com.research.assistant.entity.WritingClaimEvidence;
import com.research.assistant.entity.WritingProject;
import com.research.assistant.entity.WritingProjectPaper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.mapper.ResearchSessionPaperMapper;
import com.research.assistant.mapper.WritingClaimEvidenceMapper;
import com.research.assistant.mapper.WritingClaimMapper;
import com.research.assistant.mapper.WritingProjectMapper;
import com.research.assistant.mapper.WritingProjectPaperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Maintains the durable claim-evidence matrix for a writing project. */
@Service
public class WritingEvidenceService {

    private static final Set<String> RELATIONS = Set.of("SUPPORTS", "CONTRADICTS", "CONTEXT");

    private final WritingProjectMapper projectMapper;
    private final WritingProjectPaperMapper projectPaperMapper;
    private final WritingClaimMapper claimMapper;
    private final WritingClaimEvidenceMapper evidenceMapper;
    private final PaperMapper paperMapper;
    private final ResearchSessionMapper sessionMapper;
    private final ResearchSessionPaperMapper sessionPaperMapper;

    public WritingEvidenceService(WritingProjectMapper projectMapper,
                                  WritingProjectPaperMapper projectPaperMapper,
                                  WritingClaimMapper claimMapper,
                                  WritingClaimEvidenceMapper evidenceMapper,
                                  PaperMapper paperMapper,
                                  ResearchSessionMapper sessionMapper,
                                  ResearchSessionPaperMapper sessionPaperMapper) {
        this.projectMapper = projectMapper;
        this.projectPaperMapper = projectPaperMapper;
        this.claimMapper = claimMapper;
        this.evidenceMapper = evidenceMapper;
        this.paperMapper = paperMapper;
        this.sessionMapper = sessionMapper;
        this.sessionPaperMapper = sessionPaperMapper;
    }

    public List<WritingClaimDto> listClaims(long projectId) {
        requireProject(projectId);
        List<WritingClaim> claims = claimMapper.selectList(new LambdaQueryWrapper<WritingClaim>()
                .eq(WritingClaim::getProjectId, projectId)
                .orderByAsc(WritingClaim::getPositionNo, WritingClaim::getId));
        if (claims.isEmpty()) return List.of();

        List<Long> claimIds = claims.stream().map(WritingClaim::getId).toList();
        List<WritingClaimEvidence> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<WritingClaimEvidence>()
                        .in(WritingClaimEvidence::getClaimId, claimIds)
                        .orderByAsc(WritingClaimEvidence::getId));
        Map<Long, List<WritingClaimEvidence>> byClaim = evidence.stream()
                .collect(Collectors.groupingBy(WritingClaimEvidence::getClaimId));
        Map<Long, String> paperTitles = loadPaperTitles(evidence);
        Map<Long, String> sessionTitles = loadSessionTitles(evidence);
        return claims.stream()
                .map(claim -> toDto(claim, byClaim.getOrDefault(claim.getId(), List.of()),
                        paperTitles, sessionTitles))
                .toList();
    }

    @Transactional
    public WritingClaimDto createClaim(long projectId, WritingClaimRequest request) {
        requireProject(projectId);
        validateClaim(request);
        WritingClaim last = claimMapper.selectOne(new LambdaQueryWrapper<WritingClaim>()
                .eq(WritingClaim::getProjectId, projectId)
                .orderByDesc(WritingClaim::getPositionNo, WritingClaim::getId)
                .last("LIMIT 1"));
        WritingClaim claim = new WritingClaim();
        claim.setProjectId(projectId);
        claim.setSectionName(request.getSectionName().trim());
        claim.setClaimText(request.getClaimText().trim());
        claim.setPositionNo(last == null ? 0 : last.getPositionNo() + 1);
        claimMapper.insert(claim);
        projectMapper.touch(projectId);
        return toDto(claim, List.of(), Map.of(), Map.of());
    }

    @Transactional
    public WritingClaimDto updateClaim(long claimId, WritingClaimRequest request) {
        WritingClaim claim = requireClaim(claimId);
        validateClaim(request);
        claim.setSectionName(request.getSectionName().trim());
        claim.setClaimText(request.getClaimText().trim());
        claimMapper.updateById(claim);
        projectMapper.touch(claim.getProjectId());
        return getClaim(claimId);
    }

    @Transactional
    public void deleteClaim(long claimId) {
        WritingClaim claim = requireClaim(claimId);
        claimMapper.deleteById(claimId);
        projectMapper.touch(claim.getProjectId());
    }

    @Transactional
    public WritingEvidenceDto addEvidence(long claimId, WritingEvidenceRequest request) {
        WritingClaim claim = requireClaim(claimId);
        validateEvidence(claim, request);
        WritingClaimEvidence evidence = new WritingClaimEvidence();
        evidence.setClaimId(claimId);
        apply(evidence, request);
        evidenceMapper.insert(evidence);
        projectMapper.touch(claim.getProjectId());
        return toEvidenceDto(evidence, paperTitle(evidence.getPaperId()),
                sessionTitle(evidence.getResearchSessionId()));
    }

    @Transactional
    public WritingEvidenceDto updateEvidence(long evidenceId, WritingEvidenceRequest request) {
        WritingClaimEvidence evidence = requireEvidence(evidenceId);
        WritingClaim claim = requireClaim(evidence.getClaimId());
        validateEvidence(claim, request);
        apply(evidence, request);
        evidenceMapper.updateById(evidence);
        projectMapper.touch(claim.getProjectId());
        return toEvidenceDto(evidence, paperTitle(evidence.getPaperId()),
                sessionTitle(evidence.getResearchSessionId()));
    }

    @Transactional
    public void deleteEvidence(long evidenceId) {
        WritingClaimEvidence evidence = requireEvidence(evidenceId);
        WritingClaim claim = requireClaim(evidence.getClaimId());
        evidenceMapper.deleteById(evidenceId);
        projectMapper.touch(claim.getProjectId());
    }

    private WritingClaimDto getClaim(long claimId) {
        WritingClaim claim = requireClaim(claimId);
        List<WritingClaimEvidence> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<WritingClaimEvidence>()
                        .eq(WritingClaimEvidence::getClaimId, claimId)
                        .orderByAsc(WritingClaimEvidence::getId));
        return toDto(claim, evidence, loadPaperTitles(evidence), loadSessionTitles(evidence));
    }

    private void validateClaim(WritingClaimRequest request) {
        if (request == null || request.getSectionName() == null || request.getSectionName().isBlank()) {
            throw new IllegalArgumentException("所属章节不能为空");
        }
        if (request.getClaimText() == null || request.getClaimText().isBlank()) {
            throw new IllegalArgumentException("论点不能为空");
        }
    }

    private void validateEvidence(WritingClaim claim, WritingEvidenceRequest request) {
        if (request == null || request.getPaperId() == null) {
            throw new IllegalArgumentException("论文不能为空");
        }
        long links = projectPaperMapper.selectCount(new LambdaQueryWrapper<WritingProjectPaper>()
                .eq(WritingProjectPaper::getProjectId, claim.getProjectId())
                .eq(WritingProjectPaper::getPaperId, request.getPaperId()));
        if (links == 0) {
            throw new IllegalArgumentException("证据论文尚未加入当前写作项目");
        }
        normalizeRelation(request.getRelationType());
        if (request.getQuoteText() == null || request.getQuoteText().isBlank()) {
            throw new IllegalArgumentException("证据原文不能为空");
        }
        if (request.getPageNumber() != null && request.getPageNumber() < 1) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
        if (request.getResearchSessionId() != null
                && sessionPaperMapper.countLink(request.getResearchSessionId(), request.getPaperId()) == 0) {
            throw new IllegalArgumentException("所选研究会话不包含该论文");
        }
    }

    private void apply(WritingClaimEvidence evidence, WritingEvidenceRequest request) {
        evidence.setPaperId(request.getPaperId());
        evidence.setResearchSessionId(request.getResearchSessionId());
        evidence.setRelationType(normalizeRelation(request.getRelationType()));
        evidence.setPageNumber(request.getPageNumber());
        evidence.setLocator(trimToNull(request.getLocator()));
        evidence.setQuoteText(request.getQuoteText().trim());
        evidence.setNote(trimToNull(request.getNote()));
    }

    private String normalizeRelation(String relation) {
        String normalized = relation == null ? "" : relation.trim().toUpperCase(Locale.ROOT);
        if (!RELATIONS.contains(normalized)) {
            throw new IllegalArgumentException("证据关系必须为 SUPPORTS、CONTRADICTS 或 CONTEXT");
        }
        return normalized;
    }

    private WritingProject requireProject(long projectId) {
        WritingProject project = projectMapper.selectById(projectId);
        if (project == null) throw new IllegalArgumentException("写作项目不存在: " + projectId);
        return project;
    }

    private WritingClaim requireClaim(long claimId) {
        WritingClaim claim = claimMapper.selectById(claimId);
        if (claim == null) throw new IllegalArgumentException("写作论点不存在: " + claimId);
        return claim;
    }

    private WritingClaimEvidence requireEvidence(long evidenceId) {
        WritingClaimEvidence evidence = evidenceMapper.selectById(evidenceId);
        if (evidence == null) throw new IllegalArgumentException("写作证据不存在: " + evidenceId);
        return evidence;
    }

    private WritingClaimDto toDto(WritingClaim claim,
                                  List<WritingClaimEvidence> evidence,
                                  Map<Long, String> paperTitles,
                                  Map<Long, String> sessionTitles) {
        WritingClaimDto dto = new WritingClaimDto();
        dto.setId(claim.getId());
        dto.setProjectId(claim.getProjectId());
        dto.setSectionName(claim.getSectionName());
        dto.setClaimText(claim.getClaimText());
        dto.setPositionNo(claim.getPositionNo());
        List<WritingEvidenceDto> items = evidence.stream()
                .map(item -> toEvidenceDto(item, paperTitles.get(item.getPaperId()),
                        item.getResearchSessionId() == null
                                ? null
                                : sessionTitles.get(item.getResearchSessionId())))
                .toList();
        dto.setEvidence(items);
        dto.setSupportCount((int) evidence.stream().filter(e -> "SUPPORTS".equals(e.getRelationType())).count());
        dto.setContradictionCount((int) evidence.stream().filter(e -> "CONTRADICTS".equals(e.getRelationType())).count());
        dto.setContextCount((int) evidence.stream().filter(e -> "CONTEXT".equals(e.getRelationType())).count());
        dto.setEvidenceState(evidenceState(dto.getSupportCount(), dto.getContradictionCount()));
        dto.setCreatedAt(claim.getCreatedAt());
        dto.setUpdatedAt(claim.getUpdatedAt());
        return dto;
    }

    private String evidenceState(int supportCount, int contradictionCount) {
        if (supportCount > 0 && contradictionCount > 0) return "MIXED";
        if (contradictionCount > 0) return "CONFLICTING";
        if (supportCount > 0) return "SUPPORTED";
        return "NEEDS_EVIDENCE";
    }

    private WritingEvidenceDto toEvidenceDto(WritingClaimEvidence evidence,
                                             String paperTitle,
                                             String sessionTitle) {
        WritingEvidenceDto dto = new WritingEvidenceDto();
        dto.setId(evidence.getId());
        dto.setClaimId(evidence.getClaimId());
        dto.setPaperId(evidence.getPaperId());
        dto.setPaperTitle(paperTitle == null ? "论文 #" + evidence.getPaperId() : paperTitle);
        dto.setResearchSessionId(evidence.getResearchSessionId());
        dto.setResearchSessionTitle(sessionTitle);
        dto.setRelationType(evidence.getRelationType());
        dto.setPageNumber(evidence.getPageNumber());
        dto.setLocator(evidence.getLocator());
        dto.setQuoteText(evidence.getQuoteText());
        dto.setNote(evidence.getNote());
        dto.setCreatedAt(evidence.getCreatedAt());
        dto.setUpdatedAt(evidence.getUpdatedAt());
        return dto;
    }

    private Map<Long, String> loadPaperTitles(List<WritingClaimEvidence> evidence) {
        List<Long> ids = evidence.stream().map(WritingClaimEvidence::getPaperId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return paperMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Paper::getId, Paper::getTitle, (left, right) -> left));
    }

    private Map<Long, String> loadSessionTitles(List<WritingClaimEvidence> evidence) {
        List<Long> ids = evidence.stream().map(WritingClaimEvidence::getResearchSessionId)
                .filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return sessionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ResearchSession::getId, ResearchSession::getTitle,
                        (left, right) -> left));
    }

    private String paperTitle(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        return paper == null ? null : paper.getTitle();
    }

    private String sessionTitle(Long sessionId) {
        if (sessionId == null) return null;
        ResearchSession session = sessionMapper.selectById(sessionId);
        return session == null ? null : session.getTitle();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
