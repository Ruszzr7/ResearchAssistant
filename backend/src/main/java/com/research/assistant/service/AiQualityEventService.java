package com.research.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.assistant.dto.AiQualityEventPage;
import com.research.assistant.dto.AiQualityEventQuery;
import com.research.assistant.dto.AiQualityStatusCount;
import com.research.assistant.dto.AiQualitySummary;
import com.research.assistant.entity.AiQualityEvent;
import com.research.assistant.mapper.AiQualityEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/** 质量事件持久化服务；观测数据失败不应阻断 AI 主流程。 */
@Service
public class AiQualityEventService {

    private static final Logger log = LoggerFactory.getLogger(AiQualityEventService.class);
    private static final int MAX_RECENT_LIMIT = 200;

    private final AiQualityEventMapper mapper;
    private final TaskExecutor eventExecutor;

    public AiQualityEventService(AiQualityEventMapper mapper) {
        this(mapper, Runnable::run);
    }

    @Autowired
    public AiQualityEventService(AiQualityEventMapper mapper,
                                 @Qualifier("aiQualityEventExecutor") TaskExecutor eventExecutor) {
        this.mapper = mapper;
        this.eventExecutor = eventExecutor;
    }

    public void record(AiQualityEvent event) {
        if (event == null) {
            return;
        }
        if (isTerminalFailure(event)) {
            submit(event);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(event);
                }
            });
        } else {
            submit(event);
        }
    }

    private boolean isTerminalFailure(AiQualityEvent event) {
        return "REJECTED".equals(event.getFinalStatus()) || "FAILED".equals(event.getFinalStatus());
    }

    private void submit(AiQualityEvent event) {
        try {
            eventExecutor.execute(() -> persist(event));
        } catch (RejectedExecutionException e) {
            log.warn("AI quality event queue rejected event: runId={}, status={}",
                    event.getRunId(), event.getFinalStatus());
        }
    }

    private void persist(AiQualityEvent event) {
        try {
            mapper.insert(event);
        } catch (Exception e) {
            log.warn("AI quality event persist failed: {}", e.getMessage());
        }
    }

    public List<AiQualityEvent> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
        return mapper.selectRecent(safeLimit);
    }

    public AiQualityEventPage search(AiQualityEventQuery query) {
        AiQualityEventQuery safeQuery = query == null ? new AiQualityEventQuery() : query;
        int page = safeQuery.getPage() == null ? 1 : Math.max(1, safeQuery.getPage());
        int pageSize = safeQuery.getPageSize() == null
                ? 20 : Math.max(1, Math.min(safeQuery.getPageSize(), 100));

        LambdaQueryWrapper<AiQualityEvent> wrapper = new LambdaQueryWrapper<>();
        if (safeQuery.getPaperId() != null) wrapper.eq(AiQualityEvent::getPaperId, safeQuery.getPaperId());
        if (hasText(safeQuery.getStatus())) wrapper.eq(AiQualityEvent::getFinalStatus, safeQuery.getStatus().trim());
        if (hasText(safeQuery.getStage())) wrapper.eq(AiQualityEvent::getStage, safeQuery.getStage().trim());
        if (safeQuery.getFrom() != null) wrapper.ge(AiQualityEvent::getCreatedAt, safeQuery.getFrom());
        if (safeQuery.getTo() != null) wrapper.le(AiQualityEvent::getCreatedAt, safeQuery.getTo());
        wrapper.orderByDesc(AiQualityEvent::getCreatedAt).orderByDesc(AiQualityEvent::getId);

        Page<AiQualityEvent> result = mapper.selectPage(new Page<>(page, pageSize), wrapper);
        AiQualityEventPage response = new AiQualityEventPage();
        response.setItems(result.getRecords());
        response.setPage(page);
        response.setPageSize(pageSize);
        response.setTotal(result.getTotal());
        response.setTotalPages((result.getTotal() + pageSize - 1) / pageSize);
        return response;
    }

    public int deleteBatchBefore(LocalDateTime cutoff) {
        return mapper.deleteBatchBefore(cutoff);
    }

    public AiQualitySummary summarize(int days) {
        int safeDays = Math.max(1, Math.min(days, 365));
        List<AiQualityStatusCount> rows = mapper.summarizeSince(LocalDateTime.now().minusDays(safeDays));
        AiQualitySummary summary = new AiQualitySummary();
        summary.setDays(safeDays);
        summary.setByStatus(new LinkedHashMap<>());

        long totalLatency = 0;
        for (AiQualityStatusCount row : rows) {
            long count = row.getEventCount() == null ? 0 : row.getEventCount();
            long tokens = row.getTotalTokens() == null ? 0 : row.getTotalTokens();
            summary.setTotalEvents(summary.getTotalEvents() + count);
            summary.setTotalTokens(summary.getTotalTokens() + tokens);
            summary.getByStatus().put(row.getStatus(), count);
            if (row.getStatus() != null) {
                switch (row.getStatus()) {
                    case "PASS" -> summary.setPassedEvents(summary.getPassedEvents() + count);
                    case "REPAIRED" -> summary.setRepairedEvents(summary.getRepairedEvents() + count);
                    case "FALLBACK" -> summary.setFallbackEvents(summary.getFallbackEvents() + count);
                    case "FAILED", "REJECTED" -> summary.setFailedEvents(summary.getFailedEvents() + count);
                    default -> { }
                }
            }
            if (row.getAverageLatencyMs() != null) {
                totalLatency += Math.round(row.getAverageLatencyMs() * count);
            }
        }
        summary.setAverageLatencyMs(summary.getTotalEvents() == 0
                ? 0 : (double) totalLatency / summary.getTotalEvents());
        return summary;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
