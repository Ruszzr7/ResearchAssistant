package com.research.assistant.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.research.assistant.common.Result;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.PaperService;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 论文 REST 接口。
 */
@RestController
@RequestMapping("/api/papers")
public class PaperController {

    private final PaperService paperService;

    public PaperController(PaperService paperService) {
        this.paperService = paperService;
    }

    /** GET /api/papers?folder=1,2&keyword=transformer&sortBy=created_at&sortDir=DESC */
    @GetMapping
    public Result<IPage<Paper>> list(
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) Long tag,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "created_at") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<Long> folderIds = null;
        if (folder != null && !folder.isEmpty()) {
            folderIds = Arrays.stream(folder.split(","))
                .map(Long::parseLong)
                .collect(Collectors.toList());
        }
        return Result.ok(paperService.listWithFilters(folderIds, tag, status, keyword, sortBy, sortDir, page, size));
    }

    /** GET /api/papers/:id */
    @GetMapping("/{id}")
    public Result<Paper> getById(@PathVariable Long id) {
        Paper paper = paperService.getById(id);
        if (paper == null) {
            return Result.error(404, "论文不存在");
        }
        return Result.ok(paper);
    }

    /** POST /api/papers — 手动导入论文 */
    @PostMapping
    public Result<Paper> create(@RequestBody Paper paper) {
        return Result.ok(paperService.create(paper));
    }

    /** PUT /api/papers/:id — 编辑论文 */
    @PutMapping("/{id}")
    public Result<Paper> update(@PathVariable Long id, @RequestBody Paper paper) {
        paper.setId(id);
        return Result.ok(paperService.update(paper));
    }

    /** DELETE /api/papers/:id — 删除论文 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        paperService.delete(id);
        return Result.ok();
    }
}
