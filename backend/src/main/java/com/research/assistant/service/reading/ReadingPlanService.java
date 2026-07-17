package com.research.assistant.service.reading;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.assistant.dto.ReadingPlanDto;
import com.research.assistant.dto.ReadingPlanItemDto;
import com.research.assistant.dto.ReadingPlanItemRequest;
import com.research.assistant.dto.ReadingPlanRequest;
import com.research.assistant.constant.ReadingPlanItemStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.ReadingPlan;
import com.research.assistant.entity.ReadingPlanItem;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.ReadingPlanItemMapper;
import com.research.assistant.mapper.ReadingPlanMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.mapper.ResearchSessionPaperMapper;
import com.research.assistant.mapper.TagMapper;
import com.research.assistant.service.TagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.Set;
import java.time.LocalDateTime;
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
    private final TagMapper tagMapper;
    private final TagService tagService;
    private final ResearchSessionMapper researchSessionMapper;
    private final ResearchSessionPaperMapper researchSessionPaperMapper;

    private static final Set<String> VALID_STATUSES = Set.of(
            ReadingPlanItemStatus.TODO, ReadingPlanItemStatus.IN_PROGRESS, ReadingPlanItemStatus.DONE);
    private static final Set<String> VALID_OUTPUTS = Set.of(
            "SUMMARY", "METHOD_MAP", "RESULT_CHECK", "IMPROVEMENT", "COMPARISON");

    public ReadingPlanService(ReadingPlanMapper planMapper, ReadingPlanItemMapper itemMapper, PaperMapper paperMapper,
                              TagMapper tagMapper, TagService tagService,
                              ResearchSessionMapper researchSessionMapper,
                              ResearchSessionPaperMapper researchSessionPaperMapper) {
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
        this.paperMapper = paperMapper;
        this.tagMapper = tagMapper;
        this.tagService = tagService;
        this.researchSessionMapper = researchSessionMapper;
        this.researchSessionPaperMapper = researchSessionPaperMapper;
    }

    /**
     * 列出所有阅读计划（不含条目，但含条目统计）。
     */
    public List<ReadingPlanDto> listPlans() {
        List<ReadingPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<ReadingPlan>().orderByDesc(ReadingPlan::getUpdatedAt));
        if (plans.isEmpty()) return List.of();

        List<Long> planIds = plans.stream().map(ReadingPlan::getId).toList();
        Map<Long, List<ReadingPlanItem>> itemsByPlan = itemMapper.selectList(
                        new LambdaQueryWrapper<ReadingPlanItem>().in(ReadingPlanItem::getPlanId, planIds))
                .stream().collect(Collectors.groupingBy(ReadingPlanItem::getPlanId));

        return plans.stream().map(plan -> {
            ReadingPlanDto dto = toDto(plan);
            List<ReadingPlanItem> items = itemsByPlan.getOrDefault(plan.getId(), Collections.emptyList());
            int done = 0;
            int inProgress = 0;
            for (ReadingPlanItem item : items) {
                if (ReadingPlanItemStatus.DONE.equals(item.getStatus())) {
                    done++;
                } else if (ReadingPlanItemStatus.IN_PROGRESS.equals(item.getStatus())) {
                    inProgress++;
                }
            }
            dto.setTotalItems(items.size());
            dto.setDoneItems(done);
            dto.setInProgressItems(inProgress);
            return dto;
        }).toList();
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
        validatePlanDates(request);
        ReadingPlan plan = new ReadingPlan();
        plan.setName(request.getName().trim());
        plan.setObjective(defaultObjective(request.getObjective(), request.getName()));
        plan.setSuccessCriteria(normalizeText(request.getSuccessCriteria()));
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
        validatePlanDates(request);
        plan.setName(request.getName().trim());
        plan.setObjective(defaultObjective(request.getObjective(), request.getName()));
        plan.setSuccessCriteria(normalizeText(request.getSuccessCriteria()));
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
        if (paperId == null) throw new IllegalArgumentException("论文不能为空");
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
        item.setReadingQuestion(normalizeText(request.getReadingQuestion()));
        item.setExpectedOutput(normalizeOutput(request.getExpectedOutput()));
        item.setDeadline(request.getDeadline());
        item.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        item.setStatus(normalizeStatus(request.getStatus()));
        item.setNotes(normalizeText(request.getNotes()));
        item.setOutcome(normalizeText(request.getOutcome()));
        if (request.getResearchSessionId() != null) {
            validateResearchSession(request.getResearchSessionId(), paperId);
            item.setResearchSessionId(request.getResearchSessionId());
        }
        applyCompletionState(item);
        itemMapper.insert(item);
        touchPlan(plan);
        return toDto(item, paperTitle(paperId), paperTags(paperId), plan.getName());
    }

    /**
     * 更新条目状态 / 优先级 / deadline。
     */
    public ReadingPlanItemDto updateItem(Long planId, Long itemId, ReadingPlanItemRequest request) {
        ReadingPlanItem item = itemMapper.selectById(itemId);
        if (item == null || !item.getPlanId().equals(planId)) {
            throw new IllegalArgumentException("计划条目不存在: " + itemId);
        }
        if (request.getPaperId() != null && !request.getPaperId().equals(item.getPaperId())) {
            throw new IllegalArgumentException("计划条目不能更换论文");
        }
        if (request.getDeadline() != null) item.setDeadline(request.getDeadline());
        if (request.getPriority() != null) item.setPriority(request.getPriority());
        if (request.getStatus() != null) item.setStatus(normalizeStatus(request.getStatus()));
        if (request.getNotes() != null) item.setNotes(normalizeText(request.getNotes()));
        if (request.getReadingQuestion() != null) item.setReadingQuestion(normalizeText(request.getReadingQuestion()));
        if (request.getExpectedOutput() != null) item.setExpectedOutput(normalizeOutput(request.getExpectedOutput()));
        if (request.getOutcome() != null) item.setOutcome(normalizeText(request.getOutcome()));
        if (request.getResearchSessionId() != null) {
            validateResearchSession(request.getResearchSessionId(), item.getPaperId());
            item.setResearchSessionId(request.getResearchSessionId());
        }
        applyCompletionState(item);
        itemMapper.updateById(item);
        ReadingPlan plan = planMapper.selectById(planId);
        touchPlan(plan);
        return toDto(item, paperTitle(item.getPaperId()), paperTags(item.getPaperId()), plan.getName());
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
        touchPlan(planMapper.selectById(planId));
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
                        .ne(ReadingPlanItem::getStatus, ReadingPlanItemStatus.DONE)
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
                        .ne(ReadingPlanItem::getStatus, ReadingPlanItemStatus.DONE)
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
        Map<Long, List<String>> tagMap = paperIds.isEmpty() ? Map.of() :
                tagMapper.selectByPaperIds(paperIds).stream()
                        .collect(Collectors.groupingBy(
                                TagMapper.TagWithPaperId::getPaperId,
                                Collectors.mapping(TagMapper.TagWithPaperId::getName, Collectors.toList())));
        List<Long> planIds = items.stream().map(ReadingPlanItem::getPlanId).distinct().toList();
        Map<Long, String> planNameMap = planIds.isEmpty() ? Map.of() : planMapper.selectBatchIds(planIds).stream()
                .collect(Collectors.toMap(ReadingPlan::getId, ReadingPlan::getName));
        return items.stream()
                .map(i -> toDto(i, titleMap.getOrDefault(i.getPaperId(), ""),
                        tagMap.getOrDefault(i.getPaperId(), Collections.emptyList()),
                        planNameMap.getOrDefault(i.getPlanId(), "")))
                .toList();
    }

    private String paperTitle(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        return paper != null && paper.getTitle() != null ? paper.getTitle() : "";
    }

    private List<String> paperTags(Long paperId) {
        return tagService.getTagsByPaperId(paperId).stream()
                .map(com.research.assistant.entity.Tag::getName)
                .toList();
    }

    private ReadingPlanDto toDto(ReadingPlan plan) {
        ReadingPlanDto dto = new ReadingPlanDto();
        dto.setId(plan.getId());
        dto.setName(plan.getName());
        dto.setObjective(plan.getObjective());
        dto.setSuccessCriteria(plan.getSuccessCriteria());
        dto.setStartDate(plan.getStartDate());
        dto.setEndDate(plan.getEndDate());
        dto.setCreatedAt(plan.getCreatedAt());
        dto.setUpdatedAt(plan.getUpdatedAt());
        return dto;
    }

    private ReadingPlanItemDto toDto(ReadingPlanItem item, String paperTitle, List<String> paperTags, String planName) {
        ReadingPlanItemDto dto = new ReadingPlanItemDto();
        dto.setId(item.getId());
        dto.setPlanId(item.getPlanId());
        dto.setPaperId(item.getPaperId());
        dto.setPaperTitle(paperTitle);
        dto.setPlanName(planName);
        dto.setReadingQuestion(item.getReadingQuestion());
        dto.setExpectedOutput(item.getExpectedOutput());
        dto.setDeadline(item.getDeadline());
        dto.setPriority(item.getPriority());
        dto.setStatus(item.getStatus());
        dto.setNotes(item.getNotes());
        dto.setOutcome(item.getOutcome());
        dto.setResearchSessionId(item.getResearchSessionId());
        dto.setCompletedAt(item.getCompletedAt());
        dto.setPaperTags(paperTags);
        dto.setCreatedAt(item.getCreatedAt());
        dto.setUpdatedAt(item.getUpdatedAt());
        return dto;
    }

    private void validatePlanDates(ReadingPlanRequest request) {
        if (request.getStartDate() != null && request.getEndDate() != null
                && request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("计划结束日期不能早于开始日期");
        }
    }

    private String defaultObjective(String objective, String name) {
        String value = normalizeText(objective);
        return value == null ? name.trim() : value;
    }

    private String normalizeStatus(String status) {
        String value = status == null || status.isBlank()
                ? ReadingPlanItemStatus.TODO : status.trim().toUpperCase();
        if (!VALID_STATUSES.contains(value)) throw new IllegalArgumentException("不支持的阅读状态");
        return value;
    }

    private String normalizeOutput(String output) {
        String value = output == null || output.isBlank() ? "SUMMARY" : output.trim().toUpperCase();
        if (!VALID_OUTPUTS.contains(value)) throw new IllegalArgumentException("不支持的阅读产出类型");
        return value;
    }

    private String normalizeText(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void validateResearchSession(Long sessionId, Long paperId) {
        if (researchSessionMapper.selectById(sessionId) == null) {
            throw new IllegalArgumentException("研究会话不存在");
        }
        if (researchSessionPaperMapper.countLink(sessionId, paperId) == 0) {
            throw new IllegalArgumentException("研究会话未关联当前论文");
        }
    }

    private void applyCompletionState(ReadingPlanItem item) {
        if (ReadingPlanItemStatus.DONE.equals(item.getStatus())) {
            if (item.getOutcome() == null || item.getOutcome().isBlank()) {
                throw new IllegalArgumentException("请先记录阅读产出，再标记完成");
            }
            if (item.getCompletedAt() == null) item.setCompletedAt(LocalDateTime.now());
        } else {
            item.setCompletedAt(null);
        }
    }

    private void touchPlan(ReadingPlan plan) {
        if (plan == null) return;
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(plan);
    }
}
