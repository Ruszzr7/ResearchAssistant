package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.NoteDto;
import com.research.assistant.dto.NoteRequest;
import com.research.assistant.service.note.NoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 笔记与论文链接接口。
 */
@RestController
@RequestMapping("/api")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping("/papers/{paperId}/notes")
    public Result<List<NoteDto>> listByPaper(@PathVariable Long paperId) {
        return Result.ok(noteService.listByPaper(paperId));
    }

    @PostMapping("/papers/{paperId}/notes")
    public Result<NoteDto> create(@PathVariable Long paperId, @RequestBody @Valid NoteRequest request) {
        return Result.ok(noteService.create(paperId, request));
    }

    @PutMapping("/notes/{noteId}")
    public Result<NoteDto> update(@PathVariable Long noteId, @RequestBody @Valid NoteRequest request) {
        return Result.ok(noteService.update(noteId, request));
    }

    @DeleteMapping("/notes/{noteId}")
    public Result<Void> delete(@PathVariable Long noteId) {
        noteService.delete(noteId);
        return Result.ok();
    }

    @DeleteMapping("/papers/{paperId}/notes/{noteId}")
    public Result<Void> unlink(@PathVariable Long paperId, @PathVariable Long noteId) {
        noteService.unlink(paperId, noteId);
        return Result.ok();
    }

    @GetMapping("/notes/{noteId}/papers")
    public Result<List<Long>> listPaperIdsByNote(@PathVariable Long noteId) {
        return Result.ok(noteService.listPaperIdsByNote(noteId));
    }
}
