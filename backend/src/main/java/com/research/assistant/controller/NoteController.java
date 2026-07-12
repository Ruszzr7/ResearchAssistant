package com.research.assistant.controller;

import com.research.assistant.dto.NoteDto;
import com.research.assistant.dto.NoteRequest;
import com.research.assistant.service.note.NoteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<NoteDto>> listByPaper(@PathVariable Long paperId) {
        return ResponseEntity.ok(noteService.listByPaper(paperId));
    }

    @PostMapping("/papers/{paperId}/notes")
    public ResponseEntity<NoteDto> create(@PathVariable Long paperId, @RequestBody @Valid NoteRequest request) {
        return ResponseEntity.ok(noteService.create(paperId, request));
    }

    @PutMapping("/notes/{noteId}")
    public ResponseEntity<NoteDto> update(@PathVariable Long noteId, @RequestBody @Valid NoteRequest request) {
        return ResponseEntity.ok(noteService.update(noteId, request));
    }

    @DeleteMapping("/notes/{noteId}")
    public ResponseEntity<Void> delete(@PathVariable Long noteId) {
        noteService.delete(noteId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/papers/{paperId}/notes/{noteId}")
    public ResponseEntity<Void> unlink(@PathVariable Long paperId, @PathVariable Long noteId) {
        noteService.unlink(paperId, noteId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/notes/{noteId}/papers")
    public ResponseEntity<List<Long>> listPaperIdsByNote(@PathVariable Long noteId) {
        return ResponseEntity.ok(noteService.listPaperIdsByNote(noteId));
    }
}
