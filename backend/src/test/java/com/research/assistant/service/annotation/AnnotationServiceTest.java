package com.research.assistant.service.annotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.dto.AnnotationRequest;
import com.research.assistant.entity.PaperAnnotation;
import com.research.assistant.mapper.PaperAnnotationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AnnotationServiceTest {

    private PaperAnnotationMapper mapper;
    private AnnotationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PaperAnnotationMapper.class);
        service = new AnnotationService(mapper, new ObjectMapper());
    }

    @Test
    void shouldListAnnotationsByPaper() {
        when(mapper.selectList(any())).thenReturn(List.of(annotation(1L, "HIGHLIGHT", 2)));

        List<AnnotationDto> result = service.listByPaper(10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("HIGHLIGHT");
        assertThat(result.get(0).getPage()).isEqualTo(2);
    }

    @Test
    void shouldCreateAnnotation() {
        AnnotationRequest request = request("NOTE", 3, "#ffeb3b", "important");
        request.getCoordinates().put("source", "paper-workbench");
        request.getCoordinates().put("workbenchRunId", "run-42");
        request.getCoordinates().put("sourceEvidenceIds", List.of("e1", "e2"));

        AnnotationDto dto = service.create(10L, request);

        verify(mapper).insert(any(PaperAnnotation.class));
        assertThat(dto.getPaperId()).isEqualTo(10L);
        assertThat(dto.getType()).isEqualTo("NOTE");
        assertThat(dto.getCoordinates()).containsEntry("x", 0.1);
        assertThat(dto.getCoordinates()).containsEntry("source", "paper-workbench");
        assertThat(dto.getCoordinates()).containsEntry("workbenchRunId", "run-42");
        assertThat(dto.getCoordinates().get("sourceEvidenceIds")).isEqualTo(List.of("e1", "e2"));
    }

    @Test
    void shouldUpdateExistingAnnotation() {
        when(mapper.selectById(5L)).thenReturn(annotation(5L, "HIGHLIGHT", 1));
        AnnotationRequest request = request("UNDERLINE", 1, "#ffff00", null);

        AnnotationDto dto = service.update(5L, request);

        verify(mapper).updateById(any(PaperAnnotation.class));
        assertThat(dto.getType()).isEqualTo("UNDERLINE");
    }

    @Test
    void shouldThrowWhenUpdatingMissingAnnotation() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.update(99L, request("NOTE", 1, "#fff", "")));
    }

    @Test
    void shouldDeleteAnnotation() {
        service.delete(7L);
        verify(mapper).deleteById(7L);
    }

    private PaperAnnotation annotation(Long id, String type, int page) {
        PaperAnnotation a = new PaperAnnotation();
        a.setId(id);
        a.setPaperId(10L);
        a.setType(type);
        a.setPage(page);
        a.setColor("#ffeb3b");
        a.setCoordinatesJson("{\"x\":0.1}");
        return a;
    }

    private AnnotationRequest request(String type, int page, String color, String note) {
        AnnotationRequest r = new AnnotationRequest();
        r.setType(type);
        r.setPage(page);
        r.setColor(color);
        r.setNote(note);
        Map<String, Object> coords = new LinkedHashMap<>();
        coords.put("x", 0.1);
        r.setCoordinates(coords);
        return r;
    }
}
