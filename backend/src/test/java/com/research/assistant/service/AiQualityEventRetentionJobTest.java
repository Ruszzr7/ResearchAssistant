package com.research.assistant.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class AiQualityEventRetentionJobTest {

    @Test
    void shouldDeleteExpiredEventsInBatches() {
        AiQualityEventService service = mock(AiQualityEventService.class);
        when(service.deleteBatchBefore(any())).thenReturn(1000, 3, 0);

        new AiQualityEventRetentionJob(service, 180).purgeExpiredEvents();

        verify(service, times(3)).deleteBatchBefore(any());
    }
}
