package com.research.assistant.service.writing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.dto.NoteDto;
import com.research.assistant.dto.WritingProjectDto;
import com.research.assistant.dto.WritingProjectRequest;
import com.research.assistant.entity.WritingProject;
import com.research.assistant.entity.WritingProjectPaper;
import com.research.assistant.mapper.WritingClaimEvidenceMapper;
import com.research.assistant.mapper.WritingProjectMapper;
import com.research.assistant.mapper.WritingProjectPaperMapper;
import com.research.assistant.service.annotation.AnnotationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 写作项目服务：项目 CRUD、论文关联管理、笔记聚合。
 */
@Service
public class WritingProjectService {

    private static final Logger log = LoggerFactory.getLogger(WritingProjectService.class);

    private final WritingProjectMapper projectMapper;
    private final WritingProjectPaperMapper linkMapper;
    private final WritingClaimEvidenceMapper evidenceMapper;
    private final AnnotationService annotationService;

    public WritingProjectService(WritingProjectMapper projectMapper,
                                 WritingProjectPaperMapper linkMapper,
                                 WritingClaimEvidenceMapper evidenceMapper,
                                 AnnotationService annotationService) {
        this.projectMapper = projectMapper;
        this.linkMapper = linkMapper;
        this.evidenceMapper = evidenceMapper;
        this.annotationService = annotationService;
    }

    public List<WritingProjectDto> listProjects() {
        List<WritingProject> projects = projectMapper.selectList(
                new LambdaQueryWrapper<WritingProject>().orderByDesc(WritingProject::getUpdatedAt));
        Map<Long, List<Long>> paperIdMap = paperIdsByProjectIds(
                projects.stream().map(WritingProject::getId).filter(id -> id != null).toList());
        return projects.stream().map(p -> toDto(p, paperIdMap.getOrDefault(p.getId(), List.of()))).toList();
    }

    public WritingProjectDto getProject(Long id) {
        WritingProject project = projectMapper.selectById(id);
        if (project == null) return null;
        List<Long> paperIds = paperIdsByProjectId(id);
        return toDto(project, paperIds);
    }

    public WritingProjectDto createProject(WritingProjectRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("项目标题不能为空");
        }
        WritingProject project = new WritingProject();
        project.setTitle(request.getTitle().trim());
        project.setTopic(request.getTopic());
        project.setDraftContent(request.getDraftContent());
        project.setOutlineJson(request.getOutlineJson());
        project.setRelatedWork(request.getRelatedWork());
        projectMapper.insert(project);
        return toDto(project, List.of());
    }

    public WritingProjectDto updateProject(Long id, WritingProjectRequest request) {
        WritingProject project = projectMapper.selectById(id);
        if (project == null) throw new IllegalArgumentException("写作项目不存在: " + id);
        if (request.getTitle() != null) project.setTitle(request.getTitle().trim());
        if (request.getTopic() != null) project.setTopic(request.getTopic());
        if (request.getDraftContent() != null) project.setDraftContent(request.getDraftContent());
        if (request.getOutlineJson() != null) project.setOutlineJson(request.getOutlineJson());
        if (request.getRelatedWork() != null) project.setRelatedWork(request.getRelatedWork());
        projectMapper.updateById(project);
        return toDto(project, paperIdsByProjectId(id));
    }

    @Transactional
    public void deleteProject(Long id) {
        linkMapper.delete(new LambdaQueryWrapper<WritingProjectPaper>()
                .eq(WritingProjectPaper::getProjectId, id));
        projectMapper.deleteById(id);
    }

    @Transactional
    public void addPaper(Long projectId, Long paperId) {
        if (projectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("写作项目不存在: " + projectId);
        }
        Long count = linkMapper.selectCount(
                new LambdaQueryWrapper<WritingProjectPaper>()
                        .eq(WritingProjectPaper::getProjectId, projectId)
                        .eq(WritingProjectPaper::getPaperId, paperId));
        if (count > 0) {
            throw new IllegalArgumentException("该论文已加入当前项目");
        }
        WritingProjectPaper link = new WritingProjectPaper();
        link.setProjectId(projectId);
        link.setPaperId(paperId);
        linkMapper.insert(link);
    }

    @Transactional
    public void removePaper(Long projectId, Long paperId) {
        if (evidenceMapper.countByProjectAndPaper(projectId, paperId) > 0) {
            throw new IllegalArgumentException("该论文仍被论点证据引用，请先移除相关证据");
        }
        linkMapper.delete(
                new LambdaQueryWrapper<WritingProjectPaper>()
                        .eq(WritingProjectPaper::getProjectId, projectId)
                        .eq(WritingProjectPaper::getPaperId, paperId));
    }

    /**
     * 聚合项目关联论文中锚定到选区的 NOTE；页面 COMMENT 不进入写作素材。
     */
    public List<NoteDto> listNotesForProject(Long projectId) {
        List<Long> paperIds = paperIdsByProjectId(projectId);
        return paperIds.stream()
                .flatMap(pid -> annotationService.listByPaper(pid).stream())
                .filter(annotation -> "NOTE".equals(annotation.getType()))
                .map(this::toNoteDto)
                .toList();
    }

    private NoteDto toNoteDto(AnnotationDto annotation) {
        NoteDto dto = new NoteDto();
        dto.setId(annotation.getId());
        dto.setContent(annotation.getNote());
        dto.setPage(annotation.getPage());
        dto.setCoordinates(annotation.getCoordinates());
        Object anchor = annotation.getCoordinates() == null
                ? null : annotation.getCoordinates().get("anchorText");
        String anchorText = anchor == null ? "" : String.valueOf(anchor).trim();
        dto.setAnchorText(anchorText);
        String titleSource = anchorText.isBlank() ? annotation.getNote() : anchorText;
        dto.setTitle(shorten(titleSource, 42));
        dto.setCreatedAt(annotation.getCreatedAt());
        dto.setUpdatedAt(annotation.getUpdatedAt());
        return dto;
    }

    private String shorten(String value, int limit) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    private List<Long> paperIdsByProjectId(Long projectId) {
        return linkMapper.selectList(
                        new LambdaQueryWrapper<WritingProjectPaper>()
                                .eq(WritingProjectPaper::getProjectId, projectId)
                                .select(WritingProjectPaper::getPaperId))
                .stream()
                .map(WritingProjectPaper::getPaperId)
                .distinct()
                .toList();
    }

    private Map<Long, List<Long>> paperIdsByProjectIds(List<Long> projectIds) {
        if (projectIds.isEmpty()) return Map.of();
        return linkMapper.selectList(
                        new LambdaQueryWrapper<WritingProjectPaper>()
                                .in(WritingProjectPaper::getProjectId, projectIds))
                .stream()
                .collect(Collectors.groupingBy(
                        WritingProjectPaper::getProjectId,
                        Collectors.mapping(WritingProjectPaper::getPaperId, Collectors.toList())));
    }

    private WritingProjectDto toDto(WritingProject project, List<Long> paperIds) {
        WritingProjectDto dto = new WritingProjectDto();
        dto.setId(project.getId());
        dto.setTitle(project.getTitle());
        dto.setTopic(project.getTopic());
        dto.setOutlineJson(project.getOutlineJson());
        dto.setRelatedWork(project.getRelatedWork());
        dto.setDraftContent(project.getDraftContent());
        dto.setPaperIds(paperIds);
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());
        return dto;
    }
}
