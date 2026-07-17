package com.research.assistant.service.reading;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Rollback
class ReadingPlanServiceTest {

    @Autowired
    private ReadingPlanService service;

    @Autowired
    private ReadingPlanMapper planMapper;

    @Autowired
    private ReadingPlanItemMapper itemMapper;

    @Autowired
    private PaperMapper paperMapper;

    @Test
    void createAndGetPlan() {
        ReadingPlanRequest request = new ReadingPlanRequest();
        request.setName("暑期精读");
        request.setObjective("梳理有限块长 RSMA 的优化方法");
        request.setSuccessCriteria("形成方法对比表并确定复现实验");
        request.setStartDate(LocalDate.of(2026, 7, 1));
        request.setEndDate(LocalDate.of(2026, 8, 31));

        ReadingPlanDto dto = service.createPlan(request);
        assertThat(dto.getName()).isEqualTo("暑期精读");
        assertThat(dto.getObjective()).contains("有限块长 RSMA");
        assertThat(dto.getSuccessCriteria()).contains("方法对比表");

        ReadingPlanDto fetched = service.getPlan(dto.getId());
        assertThat(fetched.getItems()).isEmpty();
    }

    @Test
    void addItemAndWeeklyAndReminders() {
        Paper paper = new Paper();
        paper.setTitle("Test Paper");
        paperMapper.insert(paper);

        ReadingPlanRequest request = new ReadingPlanRequest();
        request.setName("本周任务");
        ReadingPlanDto plan = service.createPlan(request);

        ReadingPlanItemRequest itemReq = new ReadingPlanItemRequest();
        itemReq.setPaperId(paper.getId());
        // 使用今天，避免周日运行时“明天”落入下一周导致用例随日期波动。
        itemReq.setDeadline(LocalDate.now());
        itemReq.setPriority(2);
        service.addItem(plan.getId(), itemReq);

        List<ReadingPlanItemDto> weekly = service.weeklyList();
        List<ReadingPlanItemDto> myWeekly = weekly.stream()
                .filter(i -> "Test Paper".equals(i.getPaperTitle()))
                .toList();
        assertThat(myWeekly).hasSize(1);
        assertThat(myWeekly.get(0).getPriority()).isEqualTo(2);

        List<ReadingPlanItemDto> reminders = service.reminders();
        List<ReadingPlanItemDto> myReminders = reminders.stream()
                .filter(i -> "Test Paper".equals(i.getPaperTitle()))
                .toList();
        assertThat(myReminders).hasSize(1);
    }

    @Test
    void overdueReminderNotShownForDone() {
        Paper paper = new Paper();
        paper.setTitle("Done Paper");
        paperMapper.insert(paper);

        ReadingPlanDto plan = service.createPlan(planReq("p"));
        ReadingPlanItemRequest itemReq = new ReadingPlanItemRequest();
        itemReq.setPaperId(paper.getId());
        itemReq.setDeadline(LocalDate.now().minusDays(1));
        ReadingPlanItemDto item = service.addItem(plan.getId(), itemReq);

        // 标记为完成
        ReadingPlanItemRequest update = new ReadingPlanItemRequest();
        update.setStatus("DONE");
        update.setOutcome("确认论文的核心假设与实验结论。");
        service.updateItem(plan.getId(), item.getId(), update);

        assertThat(service.reminders().stream()
                .map(ReadingPlanItemDto::getId)
                .toList())
                .doesNotContain(item.getId());
    }

    @Test
    void deletePlanCascadesItems() {
        Paper paper = new Paper();
        paper.setTitle("x");
        paperMapper.insert(paper);

        ReadingPlanDto plan = service.createPlan(planReq("p"));
        ReadingPlanItemRequest itemReq = new ReadingPlanItemRequest();
        itemReq.setPaperId(paper.getId());
        itemReq.setDeadline(LocalDate.now());
        service.addItem(plan.getId(), itemReq);

        service.deletePlan(plan.getId());

        assertThat(service.getPlan(plan.getId())).isNull();
        assertThat(itemMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReadingPlanItem>()
                .eq(ReadingPlanItem::getPlanId, plan.getId()))).isEmpty();
    }

    @Test
    void duplicatePaperInPlanRejected() {
        Paper paper = new Paper();
        paper.setTitle("dup");
        paperMapper.insert(paper);

        ReadingPlanDto plan = service.createPlan(planReq("p"));
        ReadingPlanItemRequest req = new ReadingPlanItemRequest();
        req.setPaperId(paper.getId());
        req.setDeadline(LocalDate.now());
        service.addItem(plan.getId(), req);

        assertThatThrownBy(() -> service.addItem(plan.getId(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已在当前计划中");
    }

    @Test
    void requiresDurableOutcomeBeforeCompletion() {
        Paper paper = new Paper();
        paper.setTitle("Evidence Paper");
        paperMapper.insert(paper);
        ReadingPlanDto plan = service.createPlan(planReq("证据阅读"));

        ReadingPlanItemRequest create = new ReadingPlanItemRequest();
        create.setPaperId(paper.getId());
        create.setReadingQuestion("该方法依赖哪些信道假设？");
        create.setExpectedOutput("METHOD_MAP");
        ReadingPlanItemDto item = service.addItem(plan.getId(), create);

        ReadingPlanItemRequest invalid = new ReadingPlanItemRequest();
        invalid.setStatus("DONE");
        assertThatThrownBy(() -> service.updateItem(plan.getId(), item.getId(), invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("记录阅读产出");

        ReadingPlanItemRequest complete = new ReadingPlanItemRequest();
        complete.setStatus("DONE");
        complete.setOutcome("方法假设信道老化系数已知，并采用过时 CSIT。");
        ReadingPlanItemDto completed = service.updateItem(plan.getId(), item.getId(), complete);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getOutcome()).contains("过时 CSIT");
    }

    private ReadingPlanRequest planReq(String name) {
        ReadingPlanRequest r = new ReadingPlanRequest();
        r.setName(name);
        return r;
    }
}
