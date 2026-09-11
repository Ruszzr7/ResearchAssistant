package com.research.assistant.service.research;

import com.research.assistant.entity.ResearchSession;
import com.research.assistant.mapper.AgentTurnMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.mapper.ResearchSessionPaperMapper;
import com.research.assistant.service.agent.runtime.AgentAttachmentService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchSessionServiceDeleteTest {

    @Test
    void schedulesSessionAttachmentCleanupBeforeDeletingSessionRow() {
        ResearchSessionMapper sessionMapper = mock(ResearchSessionMapper.class);
        ResearchSessionPaperMapper sessionPaperMapper = mock(ResearchSessionPaperMapper.class);
        ResearchMessageMapper messageMapper = mock(ResearchMessageMapper.class);
        PaperMapper paperMapper = mock(PaperMapper.class);
        AgentTurnMapper agentTurnMapper = mock(AgentTurnMapper.class);
        AgentAttachmentService attachmentService = mock(AgentAttachmentService.class);

        ResearchSession session = new ResearchSession();
        session.setId(5L);
        when(sessionMapper.selectById(5L)).thenReturn(session);
        when(sessionMapper.deleteById(5L)).thenReturn(1);

        ResearchSessionService service = new ResearchSessionService(
                sessionMapper, sessionPaperMapper, messageMapper, paperMapper,
                agentTurnMapper, attachmentService);

        service.delete(5L);

        verify(attachmentService).deleteAfterCommitBySession(5L);
        verify(sessionMapper).deleteById(5L);
    }
}
