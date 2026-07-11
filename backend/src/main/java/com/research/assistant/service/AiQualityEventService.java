package com.research.assistant.service;

import com.research.assistant.dto.AiQualityStatusCount;
import com.research.assistant.dto.AiQualitySummary;
import com.research.assistant.entity.AiQualityEvent;
import com.research.assistant.mapper.AiQualityEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

/** 质量事件持久化服务；观测数据失败不应阻断 AI 主流程。 */
@Service
public class AiQualityEventService {

    private static final Logger log = LoggerFactory.getLogger(AiQualityEventService.class);
    private static final int MAX_RECENT_LIMIT = 200;

    private final AiQualityEventMapper mapper;

    public AiQualityEventService(AiQualityEventMapper mapper) {
        this.mapper = mapper;
    }

    public void record(AiQualityEvent event) {
        if (event == null) {
            return;
        }
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
                    case "FAILED" -> summary.setFailedEvents(summary.getFailedEvents() + count);
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
}
