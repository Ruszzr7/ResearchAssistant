package com.research.assistant.service.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperWorkbenchRunRecord;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.mapper.ResearchSessionPaperMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResearchSessionHistoryServiceTest {

    @Test
    void doesNotCreateConversationHistoryForFailedRuns() {
        ResearchSessionMapper sessionMapper = mock(ResearchSessionMapper.class);
        ResearchSessionPaperMapper sessionPaperMapper = mock(ResearchSessionPaperMapper.class);
        PaperWorkbenchRunMapper runMapper = mock(PaperWorkbenchRunMapper.class);
        PaperMapper paperMapper = mock(PaperMapper.class);
        ResearchSessionHistoryService service = new ResearchSessionHistoryService(
                sessionMapper, sessionPaperMapper, runMapper, paperMapper, new ObjectMapper());

        PaperWorkbenchRunRecord failed = new PaperWorkbenchRunRecord();
        failed.setStatus("FAILED");
        failed.setPaperIdsJson("[186]");
        when(runMapper.selectUnlinked(200)).thenReturn(List.of(failed));

        assertThat(service.backfillRecent()).isZero();
        verifyNoInteractions(sessionMapper, sessionPaperMapper, paperMapper);
    }
}
