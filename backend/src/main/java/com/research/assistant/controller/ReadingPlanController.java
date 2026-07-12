package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.ReadingPlanDto;
import com.research.assistant.dto.ReadingPlanItemDto;
import com.research.assistant.dto.ReadingPlanItemRequest;
import com.research.assistant.dto.ReadingPlanRequest;
import com.research.assistant.service.reading.ReadingPlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 阅读计划接口。
 */
@RestController
@RequestMapping("/api/reading-plans")
public class ReadingPlanController {

    private final ReadingPlanService readingPlanService;

    public ReadingPlanController(ReadingPlanService readingPlanService) {
        this.readingPlanService = readingPlanService;
    }

    @GetMapping
    public Result<List<ReadingPlanDto>> list() {
        return Result.ok(readingPlanService.listPlans());
    }

    @PostMapping
    public Result<ReadingPlanDto> create(@RequestBody @Valid ReadingPlanRequest request) {
        return Result.ok(readingPlanService.createPlan(request));
    }

    @GetMapping("/{id}")
    public Result<ReadingPlanDto> get(@PathVariable Long id) {
        ReadingPlanDto dto = readingPlanService.getPlan(id);
        if (dto == null) return Result.error(404, "阅读计划不存在");
        return Result.ok(dto);
    }

    @PutMapping("/{id}")
    public Result<ReadingPlanDto> update(@PathVariable Long id, @RequestBody @Valid ReadingPlanRequest request) {
        return Result.ok(readingPlanService.updatePlan(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        readingPlanService.deletePlan(id);
        return Result.ok();
    }

    @PostMapping("/{id}/items")
    public Result<ReadingPlanItemDto> addItem(@PathVariable Long id, @RequestBody @Valid ReadingPlanItemRequest request) {
        return Result.ok(readingPlanService.addItem(id, request));
    }

    @PutMapping("/{id}/items/{itemId}")
    public Result<ReadingPlanItemDto> updateItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestBody @Valid ReadingPlanItemRequest request) {
        return Result.ok(readingPlanService.updateItem(id, itemId, request));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public Result<Void> deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        readingPlanService.deleteItem(id, itemId);
        return Result.ok();
    }

    @GetMapping("/weekly")
    public Result<List<ReadingPlanItemDto>> weekly() {
        return Result.ok(readingPlanService.weeklyList());
    }

    @GetMapping("/reminders")
    public Result<List<ReadingPlanItemDto>> reminders() {
        return Result.ok(readingPlanService.reminders());
    }
}
