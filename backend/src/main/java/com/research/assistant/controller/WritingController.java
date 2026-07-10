package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.*;
import com.research.assistant.service.writing.WritingAssistantService;
import com.research.assistant.service.writing.WritingProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 写作辅助模块接口。
 */
@RestController
@RequestMapping("/api/writing")
public class WritingController {

    private final WritingProjectService writingProjectService;
    private final WritingAssistantService writingAssistantService;

    public WritingController(WritingProjectService writingProjectService,
                             WritingAssistantService writingAssistantService) {
        this.writingProjectService = writingProjectService;
        this.writingAssistantService = writingAssistantService;
    }

    // ===== 写作项目 CRUD =====

    @GetMapping("/projects")
    public Result<List<WritingProjectDto>> listProjects() {
        return Result.ok(writingProjectService.listProjects());
    }

    @PostMapping("/projects")
    public Result<WritingProjectDto> createProject(@RequestBody WritingProjectRequest request) {
        return Result.ok(writingProjectService.createProject(request));
    }

    @GetMapping("/projects/{id}")
    public Result<WritingProjectDto> getProject(@PathVariable Long id) {
        WritingProjectDto dto = writingProjectService.getProject(id);
        if (dto == null) return Result.error(404, "写作项目不存在");
        return Result.ok(dto);
    }

    @PutMapping("/projects/{id}")
    public Result<WritingProjectDto> updateProject(@PathVariable Long id,
                                                    @RequestBody WritingProjectRequest request) {
        return Result.ok(writingProjectService.updateProject(id, request));
    }

    @DeleteMapping("/projects/{id}")
    public Result<Void> deleteProject(@PathVariable Long id) {
        writingProjectService.deleteProject(id);
        return Result.ok();
    }

    // ===== 项目-论文关联 =====

    @PostMapping("/projects/{id}/papers")
    public Result<Void> addPaper(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        Long paperId = body.get("paperId");
        if (paperId == null) return Result.error(400, "paperId 不能为空");
        writingProjectService.addPaper(id, paperId);
        return Result.ok();
    }

    @DeleteMapping("/projects/{id}/papers/{paperId}")
    public Result<Void> removePaper(@PathVariable Long id, @PathVariable Long paperId) {
        writingProjectService.removePaper(id, paperId);
        return Result.ok();
    }

    // ===== 笔记聚合 =====

    @GetMapping("/projects/{id}/notes")
    public Result<List<NoteDto>> listProjectNotes(@PathVariable Long id) {
        return Result.ok(writingProjectService.listNotesForProject(id));
    }

    // ===== AI 生成/检查 =====

    @PostMapping("/outline")
    public Result<OutlineDto> generateOutline(@RequestBody OutlineRequest request) {
        return Result.ok(writingAssistantService.generateOutline(
                request.getTopic(), request.getStyle(), request.getLanguage()));
    }

    @PostMapping("/related-work")
    public Result<RelatedWorkDto> generateRelatedWork(@RequestBody RelatedWorkRequest request) {
        return Result.ok(writingAssistantService.generateRelatedWork(
                request.getPaperIds(), request.getTopic(), request.getStyle()));
    }

    @PostMapping("/citation-check")
    public Result<CitationCheckDto> checkCitations(@RequestBody CitationCheckRequest request) {
        return Result.ok(writingAssistantService.checkCitations(
                request.getParagraph(), request.getPaperIds()));
    }
}
