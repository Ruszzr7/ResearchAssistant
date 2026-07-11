package com.research.assistant.service;

import com.research.assistant.dto.AiQualityStatusCount;
import com.research.assistant.dto.AiQualitySummary;
import com.research.assistant.entity.AiQualityEvent;
import com.research.assistant.mapper.AiQualityEventMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiQualityEventServiceTest {

    private final AiQualityEventMapper mapper = mock(AiQualityEventMapper.class);
    private final AiQualityEventService service = new AiQualityEventService(mapper);

    @Test
    void shouldAggregateQualityStatuses() {
        AiQualityStatusCount passed = row("PASS", 3L, 100.0, 300L);
        AiQualityStatusCount repaired = row("REPAIRED", 1L, 200.0, 50L);
        AiQualityStatusCount failed = row("FAILED", 1L, 300.0, 20L);
        when(mapper.summarizeSince(any())).thenReturn(List.of(passed, repaired, failed));

        AiQualitySummary summary = service.summarize(30);

        assertThat(summary.getDays()).isEqualTo(30);
        assertThat(summary.getTotalEvents()).isEqualTo(5);
        assertThat(summary.getPassedEvents()).isEqualTo(3);
        assertThat(summary.getRepairedEvents()).isEqualTo(1);
        assertThat(summary.getFailedEvents()).isEqualTo(1);
        assertThat(summary.getTotalTokens()).isEqualTo(370);
        assertThat(summary.getAverageLatencyMs()).isEqualTo(160.0);
        assertThat(summary.getByStatus()).containsEntry("PASS", 3L);
    }

    @Test
    void shouldCapRecentLimitAndIgnorePersistenceFailure() {
        AiQualityEvent event = new AiQualityEvent();
        when(mapper.selectRecent(200)).thenReturn(List.of(event));
        doThrow(new RuntimeException("database unavailable")).when(mapper).insert(any(AiQualityEvent.class));

        service.record(event);
        List<AiQualityEvent> recent = service.recent(9999);

        assertThat(recent).hasSize(1);
        verify(mapper).selectRecent(200);
    }

    private AiQualityStatusCount row(String status, long count, double latency, long tokens) {
        AiQualityStatusCount row = new AiQualityStatusCount();
        row.setStatus(status);
        row.setEventCount(count);
        row.setAverageLatencyMs(latency);
        row.setTotalTokens(tokens);
        return row;
    }
}
