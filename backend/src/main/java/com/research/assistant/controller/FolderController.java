package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.entity.Folder;
import com.research.assistant.service.FolderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 文件夹 REST 接口。
 */
@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    /** GET /api/folders — 获取文件夹树 */
    @GetMapping
    public Result<List<Folder>> getTree() {
        return Result.ok(folderService.getTree());
    }

    /** POST /api/folders — 新建文件夹 */
    @PostMapping
    public Result<Folder> create(@RequestBody Folder folder) {
        return Result.ok(folderService.create(folder));
    }

    /** PUT /api/folders/:id — 重命名/移动 */
    @PutMapping("/{id}")
    public Result<Folder> update(@PathVariable Long id, @RequestBody Folder folder) {
        folder.setId(id);
        return Result.ok(folderService.update(folder));
    }

    /** DELETE /api/folders/:id — 删除文件夹 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        folderService.delete(id);
        return Result.ok();
    }

    /** PUT /api/folders/:id/move — 拖拽移动（更新 parentId + sortOrder） */
    @PutMapping("/{id}/move")
    public Result<Void> move(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long parentId = body.get("parentId") != null ? ((Number) body.get("parentId")).longValue() : null;
        Integer sortOrder = body.get("sortOrder") != null ? ((Number) body.get("sortOrder")).intValue() : 0;
        folderService.move(id, parentId, sortOrder);
        return Result.ok();
    }
}
