package com.research.assistant.service.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.NoteDto;
import com.research.assistant.dto.NoteRequest;
import com.research.assistant.entity.Note;
import com.research.assistant.entity.PaperNoteLink;
import com.research.assistant.mapper.NoteMapper;
import com.research.assistant.mapper.PaperNoteLinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class NoteServiceTest {

    private NoteMapper noteMapper;
    private PaperNoteLinkMapper linkMapper;
    private NoteService service;

    @BeforeEach
    void setUp() {
        noteMapper = mock(NoteMapper.class);
        linkMapper = mock(PaperNoteLinkMapper.class);
        service = new NoteService(noteMapper, linkMapper, new ObjectMapper());
    }

    @Test
    void shouldCreateNoteAndLink() {
        NoteRequest request = new NoteRequest();
        request.setTitle("t");
        request.setContent("c");
        request.setPage(2);
        request.setAnchorText("anchor");
        request.setCoordinates(Map.of("x", 0.1));

        when(noteMapper.insert(any(Note.class))).thenAnswer(inv -> {
            Note n = inv.getArgument(0);
            n.setId(10L);
            return 1;
        });
        when(linkMapper.insert(any(PaperNoteLink.class))).thenAnswer(inv -> {
            PaperNoteLink l = inv.getArgument(0);
            l.setId(20L);
            return 1;
        });

        NoteDto dto = service.create(1L, request);

        assertThat(dto.getId()).isEqualTo(10L);
        assertThat(dto.getLinkId()).isEqualTo(20L);
        assertThat(dto.getPage()).isEqualTo(2);
        verify(noteMapper).insert(any(Note.class));
        verify(linkMapper).insert(any(PaperNoteLink.class));
    }

    @Test
    void shouldDeleteNoteAndLinks() {
        service.delete(5L);
        verify(linkMapper).delete(any());
        verify(noteMapper).deleteById(5L);
    }

    @Test
    void shouldThrowWhenUpdatingMissingNote() {
        when(noteMapper.selectById(99L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.update(99L, new NoteRequest()));
    }
}
