package com.research.assistant.service.reading;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.assistant.dto.ReadingPlanDto;
import com.research.assistant.dto.ReadingPlanItemDto;
import com.research.assistant.dto.ReadingPlanItemRequest;
import com.research.assistant.dto.ReadingPlanRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.ReadingPlan;
import com.research.assistant.entity.ReadingPlanItem;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.ReadingPlanItemMapper;
import com.research.assistant.mapper.ReadingPlanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 阅读计划服务：计划 CRUD、按周查询、逾期/即将到期提醒。
 */
@Service
public class ReadingPlanService {

    private static final Logger log = LoggerFactory.getLogger(ReadingPlanService.class);

    private final ReadingPlanMapper planMapper;
    private final ReadingPlanItemMapper itemMapper;
    private final PaperMapper paperMapper;

    public ReadingPlanService(ReadingPlanMapper planMapper, ReadingPlanItemMapper itemMapper, PaperMapper paperMapper) {
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
        this.paperMapper = paperMapper;
    }

    /**
     * 列出所有阅读计划（不含条目）。
     */
    public List<ReadingPlanDto> listPlans() {
        List<ReadingPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<ReadingPlan>().orderByDesc(ReadingPlan::getUpdatedAt));
        return plans.stream().map(this::toDto).toList();
    }

    /**
     * 获取单个计划及其条目。
     */
    public ReadingPlanDto getPlan(Long id) {
        ReadingPlan plan = planMapper.selectById(id);
        if (plan == null) return null;
        ReadingPlanDto dto = toDto(plan);
        dto.setItems(listItems(id));
        return dto;
    }

    /**
     * 创建计划。
     */
    public ReadingPlanDto createPlan(ReadingPlanRequest request) {
        ReadingPlan plan = new ReadingPlan();
        plan.setName(request.getName());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        planMapper.insert(plan);
        return toDto(plan);
    }

    /**
     * 更新计划。
     */
    public ReadingPlanDto updatePlan(Long id, ReadingPlanRequest request) {
        ReadingPlan plan = planMapper.selectById(id);
        if (plan == null) throw new IllegalArgumentException("阅读计划不存在: " + id);
        plan.setName(request.getName());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        planMapper.updateById(plan);
        return toDto(plan);
    }

    /**
     * 删除计划及其条目。
     */
    @Transactional
    public void deletePlan(Long id) {
        itemMapper.delete(new LambdaQueryWrapper<ReadingPlanItem>().eq(ReadingPlanItem::getPlanId, id));
        planMapper.deleteById(id);
    }

    /**
     * 查询计划下所有条目。
     */
    public List<ReadingPlanItemDto> listItems(Long planId) {
        List<ReadingPlanItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ReadingPlanItem>()
                        .eq(ReadingPlanItem::getPlanId, planId)
                        .orderByAsc(ReadingPlanItem::getDeadline)
                        .orderByDesc(ReadingPlanItem::getPriority));
        return enrichAndMap(items);
    }

    /**
     * 添加条目到计划。
     */
    public ReadingPlanItemDto addItem(Long planId, ReadingPlanItemRequest request) {
        ReadingPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new IllegalArgumentException("阅读计划不存在: " + planId);

        Long paperId = request.getPaperId();
        if (paperMapper.selectById(paperId) == null) {
            throw new IllegalArgumentException("论文不存在: " + paperId);
        }

        Long existing = itemMapper.selectCount(
                new LambdaQueryWrapper<ReadingPlanItem>()
                        .eq(ReadingPlanItem::getPlanId, planId)
                        .eq(ReadingPlanItem::getPaperId, paperId));
        if (existing > 0) {
            throw new IllegalArgumentException("该论文已在当前计划中");
        }

        ReadingPlanItem item = new ReadingPlanItem();
        item.setPlanId(planId);
        item.setPaperId(paperId);
        item.setDeadline(request.getDeadline());
        item.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        item.setStatus(request.getStatus() != null ? request.getStatus() : "TODO");
        itemMapper.insert(item);
        return toDto(item, paperTitle(paperId));
    }

    /**
     * 更新条目状态 / 优先级 / deadline。
     */
    public ReadingPlanItemDto updateItem(Long planId, Long itemId, ReadingPlanItemRequest request) {
        ReadingPlanItem item = itemMapper.selectById(itemId);
        if (item == null || !item.getPlanId().equals(planId)) {
            throw new IllegalArgumentException("计划条目不存在: " + itemId);
        }
        if (request.getDeadline() != null) item.setDeadline(request.getDeadline());
        if (request.getPriority() != null) item.setPriority(request.getPriority());
        if (request.getStatus() != null) item.setStatus(request.getStatus());
        itemMapper.updateById(item);
        return toDto(item, paperTitle(item.getPaperId()));
    }

    /**
     * 删除条目。
     */
    public void deleteItem(Long planId, Long itemId) {
        ReadingPlanItem item = itemMapper.selectById(itemId);
        if (item == null || !item.getPlanId().equals(planId)) {
            throw new IllegalArgumentException("计划条目不存在: " + itemId);
        }
        itemMapper.deleteById(itemId);
    }

    /**
     * 本周要读清单：当前周 deadline 落在本周、或状态非 DONE 的条目。
     */
    public List<ReadingPlanItemDto> weeklyList() {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

        List<ReadingPlanItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ReadingPlanItem>()
                        .ne(ReadingPlanItem::getStatus, "DONE")
                        .and(w -> w.between(ReadingPlanItem::getDeadline, weekStart, weekEnd)
                                .or()
                                .isNull(ReadingPlanItem::getDeadline))
                        .orderByAsc(ReadingPlanItem::getDeadline)
                        .orderByDesc(ReadingPlanItem::getPriority));
        return enrichAndMap(items);
    }

    /**
     * 提醒列表：已逾期或 3 天内到期且未完成的条目。
     */
    public List<ReadingPlanItemDto> reminders() {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(3);

        List<ReadingPlanItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<ReadingPlanItem>()
                        .ne(ReadingPlanItem::getStatus, "DONE")
                        .le(ReadingPlanItem::getDeadline, threshold)
                        .orderByAsc(ReadingPlanItem::getDeadline));
        return enrichAndMap(items);
    }

    private List<ReadingPlanItemDto> enrichAndMap(List<ReadingPlanItem> items) {
        List<Long> paperIds = items.stream()
                .map(ReadingPlanItem::getPaperId)
                .distinct()
                .toList();
        Map<Long, String> titleMap = paperIds.isEmpty() ? Map.of() :
                paperMapper.selectBatchIds(paperIds).stream()
                        .collect(Collectors.toMap(Paper::getId, p -> p.getTitle() == null ? "" : p.getTitle(), (a, b) -> a));
        return items.stream()
                .map(i -> toDto(i, titleMap.getOrDefault(i.getPaperId(), "")))
                .toList();
    }

    private String paperTitle(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        return paper != null && paper.getTitle() != null ? paper.getTitle() : "";
    }

    private ReadingPlanDto toDto(ReadingPlan plan) {
        ReadingPlanDto dto = new ReadingPlanDto();
        dto.setId(plan.getId());
        dto.setName(plan.getName());
        dto.setStartDate(plan.getStartDate());
        dto.setEndDate(plan.getEndDate());
        dto.setCreatedAt(plan.getCreatedAt());
        dto.setUpdatedAt(plan.getUpdatedAt());
        return dto;
    }

    private ReadingPlanItemDto toDto(ReadingPlanItem item, String paperTitle) {
        ReadingPlanItemDto dto = new ReadingPlanItemDto();
        dto.setId(item.getId());
        dto.setPlanId(item.getPlanId());
        dto.setPaperId(item.getPaperId());
        dto.setPaperTitle(paperTitle);
        dto.setDeadline(item.getDeadline());
        dto.setPriority(item.getPriority());
        dto.setStatus(item.getStatus());
        dto.setCreatedAt(item.getCreatedAt());
        dto.setUpdatedAt(item.getUpdatedAt());
        return dto;
    }
}
