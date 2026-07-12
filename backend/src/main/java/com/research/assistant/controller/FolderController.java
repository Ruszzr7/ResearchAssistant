package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.FolderMoveRequest;
import com.research.assistant.dto.FolderRequest;
import com.research.assistant.entity.Folder;
import com.research.assistant.service.FolderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping
    public Result<List<Folder>> getTree() {
        return Result.ok(folderService.getTree());
    }

    @PostMapping
    public Result<Folder> create(@RequestBody @Valid FolderRequest request) {
        return Result.ok(folderService.create(toFolder(request, null)));
    }

    @PutMapping("/{id}")
    public Result<Folder> update(@PathVariable Long id, @RequestBody @Valid FolderRequest request) {
        return Result.ok(folderService.update(toFolder(request, id)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        folderService.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/move")
    public Result<Void> move(@PathVariable Long id, @RequestBody @Valid FolderMoveRequest request) {
        folderService.move(id, request.getParentId(), request.getSortOrder() == null ? 0 : request.getSortOrder());
        return Result.ok();
    }

    private Folder toFolder(FolderRequest request, Long id) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setName(request.getName());
        folder.setParentId(request.getParentId());
        folder.setSortOrder(request.getSortOrder());
        return folder;
    }
}
