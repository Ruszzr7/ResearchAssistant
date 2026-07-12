package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.PaperTagIdsRequest;
import com.research.assistant.dto.TagRequest;
import com.research.assistant.entity.Tag;
import com.research.assistant.service.TagService;
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
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public Result<List<Tag>> list() {
        return Result.ok(tagService.listAll());
    }

    @GetMapping("/{id}")
    public Result<Tag> get(@PathVariable Long id) {
        return Result.ok(tagService.getById(id));
    }

    @PostMapping
    public Result<Tag> create(@RequestBody @Valid TagRequest request) {
        return Result.ok(tagService.create(request.getName().trim()));
    }

    @PutMapping("/{id}")
    public Result<Tag> rename(@PathVariable Long id, @RequestBody @Valid TagRequest request) {
        return Result.ok(tagService.rename(id, request.getName().trim()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.ok();
    }

    @GetMapping("/papers/{paperId}/tags")
    public Result<List<Tag>> getPaperTags(@PathVariable Long paperId) {
        return Result.ok(tagService.getTagsByPaperId(paperId));
    }

    @PostMapping("/papers/{paperId}/tags")
    public Result<Void> setPaperTags(@PathVariable Long paperId,
                                     @RequestBody @Valid PaperTagIdsRequest request) {
        tagService.setPaperTags(paperId, request.getTagIds());
        return Result.ok();
    }
}
