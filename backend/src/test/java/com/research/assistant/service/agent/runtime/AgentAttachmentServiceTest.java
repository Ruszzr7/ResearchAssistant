package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentAttachmentRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.mapper.AgentAttachmentMapper;
import com.research.assistant.mapper.AgentTurnMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAttachmentServiceTest {

    @TempDir Path tempDir;

    @Test
    void storesOnlyAllowedContentInsideControlledRoot() throws Exception {
        AgentAttachmentMapper attachmentMapper = mock(AgentAttachmentMapper.class);
        AgentTurnMapper turnMapper = mock(AgentTurnMapper.class);
        AgentTurnRecord turn = new AgentTurnRecord();
        turn.setId(7L);
        turn.setTurnId("turn-7");
        turn.setSessionId(3L);
        when(turnMapper.selectById(7L)).thenReturn(turn);
        AgentAttachmentService service = new AgentAttachmentService(
                attachmentMapper, turnMapper, tempDir.toString());

        AgentAttachmentRecord stored = service.store(7L, "FILE", "../paper.pdf",
                "application/pdf", "%PDF-test".getBytes());

        assertThat(stored.getOriginalName()).isEqualTo("paper.pdf");
        assertThat(stored.getStoragePath()).startsWith("turn-7/");
        assertThat(Files.readString(service.resolveContent(stored))).isEqualTo("%PDF-test");
        assertThat(service.resolveContent(stored)).startsWith(tempDir.toAbsolutePath());
        verify(attachmentMapper).insert(any(AgentAttachmentRecord.class));
    }

    @Test
    void rejectsUnsupportedOrOversizedContent() {
        AgentAttachmentMapper attachmentMapper = mock(AgentAttachmentMapper.class);
        AgentTurnMapper turnMapper = mock(AgentTurnMapper.class);
        AgentTurnRecord turn = new AgentTurnRecord();
        turn.setTurnId("turn-8");
        turn.setSessionId(3L);
        when(turnMapper.selectById(8L)).thenReturn(turn);
        AgentAttachmentService service = new AgentAttachmentService(
                attachmentMapper, turnMapper, tempDir.toString());

        assertThatThrownBy(() -> service.store(8L, "FILE", "x.exe",
                "application/octet-stream", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.store(8L, "FILE", "huge.pdf",
                "application/pdf", new byte[(int) AgentAttachmentService.MAX_ATTACHMENT_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stagesByConversationAndClaimsExactlyOneTurn() {
        AgentAttachmentMapper attachmentMapper = mock(AgentAttachmentMapper.class);
        AgentTurnMapper turnMapper = mock(AgentTurnMapper.class);
        when(turnMapper.lockSession(3L)).thenReturn(3L);
        AgentAttachmentService service = new AgentAttachmentService(
                attachmentMapper, turnMapper, tempDir.toString());

        AgentAttachmentRecord staged = service.stage(3L, "FORMULA_TEXT", "formula.tex",
                "application/x-latex", "x^2".getBytes());
        when(attachmentMapper.selectByAttachmentId(staged.getAttachmentId())).thenReturn(staged);
        when(attachmentMapper.claim(staged.getAttachmentId(), 3L, 9L)).thenReturn(1);

        assertThat(staged.getSessionId()).isEqualTo(3L);
        assertThat(staged.getTurnId()).isNull();
        assertThat(staged.getPreviewText()).isEqualTo("x^2");
        assertThat(service.claim(9L, 3L, java.util.List.of(staged.getAttachmentId())))
                .singleElement().extracting(AgentAttachmentRecord::getTurnId).isEqualTo(9L);
    }

    @Test
    void infersWordAndImageTypesWhenBrowserDoesNotSendAContentType() {
        AgentAttachmentMapper attachmentMapper = mock(AgentAttachmentMapper.class);
        AgentTurnMapper turnMapper = mock(AgentTurnMapper.class);
        AgentTurnRecord turn = new AgentTurnRecord();
        turn.setId(10L);
        turn.setTurnId("turn-10");
        turn.setSessionId(3L);
        when(turnMapper.selectById(10L)).thenReturn(turn);
        AgentAttachmentService service = new AgentAttachmentService(
                attachmentMapper, turnMapper, tempDir.toString());

        AgentAttachmentRecord docx = service.store(10L, "FILE", "paper.docx",
                "application/octet-stream", new byte[]{1, 2, 3});
        AgentAttachmentRecord image = service.store(10L, "FILE", "figure.png",
                null, new byte[]{4, 5, 6});

        assertThat(docx.getMediaType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(image.getMediaType()).isEqualTo("image/png");
    }
}
