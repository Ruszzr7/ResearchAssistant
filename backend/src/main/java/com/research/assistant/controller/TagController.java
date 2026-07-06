package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.entity.Tag;
import com.research.assistant.service.TagService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 标签 REST 接口 —— 标签字典与论文-标签关联管理。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /** GET /api/tags — 获取所有标签 */
    @GetMapping
    public Result<List<Tag>> list() {
        return Result.ok(tagService.listAll());
    }

    /** GET /api/tags/{id} — 获取单个标签 */
    @GetMapping("/{id}")
    public Result<Tag> get(@PathVariable Long id) {
        return Result.ok(tagService.getById(id));
    }

    /** POST /api/tags — 创建标签 */
    @PostMapping
    public Result<Tag> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        return Result.ok(tagService.create(name));
    }

    /** PUT /api/tags/{id} — 重命名标签 */
    @PutMapping("/{id}")
    public Result<Tag> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        return Result.ok(tagService.rename(id, name));
    }

    /** DELETE /api/tags/{id} — 删除标签 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.ok();
    }

    /** GET /api/papers/{paperId}/tags — 获取论文标签 */
    @GetMapping("/papers/{paperId}/tags")
    public Result<List<Tag>> getPaperTags(@PathVariable Long paperId) {
        return Result.ok(tagService.getTagsByPaperId(paperId));
    }

    /** POST /api/papers/{paperId}/tags — 批量设置论文标签 */
    @PostMapping("/papers/{paperId}/tags")
    public Result<Void> setPaperTags(@PathVariable Long paperId,
                                         @RequestBody Map<String, List<Long>> body) {
        tagService.setPaperTags(paperId, body.get("tagIds"));
        return Result.ok();
    }
}
