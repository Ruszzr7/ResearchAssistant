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

        AnnotationDto dto = service.update(10L, 5L, request);

        verify(mapper).updateById(any(PaperAnnotation.class));
        assertThat(dto.getType()).isEqualTo("UNDERLINE");
    }

    @Test
    void shouldCompleteOnlyPageComments() {
        when(mapper.selectById(6L)).thenReturn(annotation(6L, "COMMENT", 4));
        AnnotationRequest request = request("COMMENT", 4, "#f44336", "review this claim");
        request.setCompleted(true);

        AnnotationDto dto = service.update(10L, 6L, request);

        assertThat(dto.getCompleted()).isTrue();
        assertThat(dto.getCompletedAt()).isNotNull();
        verify(mapper).updateById(org.mockito.ArgumentMatchers.<PaperAnnotation>argThat(
                entity -> Boolean.TRUE.equals(entity.getCompleted())));

        AnnotationRequest noteRequest = request("NOTE", 4, "#f44336", "selection note");
        noteRequest.setCompleted(true);
        when(mapper.selectById(7L)).thenReturn(annotation(7L, "NOTE", 4));
        assertThat(service.update(10L, 7L, noteRequest).getCompleted()).isFalse();
    }

    @Test
    void shouldThrowWhenUpdatingMissingAnnotation() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.update(10L, 99L, request("NOTE", 1, "#fff", "content")));
    }

    @Test
    void shouldRejectMarkerAnnotationsWithoutTheirRequiredAnchor() {
        AnnotationRequest note = request("NOTE", 1, "#f44336", "content");
        note.setCoordinates(Map.of("anchorText", "text"));
        assertThrows(IllegalArgumentException.class, () -> service.create(10L, note));

        AnnotationRequest comment = request("COMMENT", 1, "#f44336", "content");
        comment.setCoordinates(Map.of("notePosition", Map.of("x", 0.2, "y", 0.2)));
        assertThrows(IllegalArgumentException.class, () -> service.create(10L, comment));
    }

    @Test
    void shouldAcceptSelectionAnchoredCommentsAndLegacyPointComments() {
        AnnotationRequest selectionComment = request("COMMENT", 1, "#f44336", "selection comment");
        selectionComment.getCoordinates().remove("anchorPoint");
        selectionComment.getCoordinates().put("anchorQuads", List.of(Map.of(
                "x1", 0.1, "y1", 0.2, "x2", 0.3, "y2", 0.2,
                "x3", 0.3, "y3", 0.16, "x4", 0.1, "y4", 0.16)));

        assertThat(service.create(10L, selectionComment).getType()).isEqualTo("COMMENT");
        assertThat(service.create(10L, request("COMMENT", 1, "#f44336", "legacy point comment")).getType())
                .isEqualTo("COMMENT");
    }

    @Test
    void shouldDeleteAnnotation() {
        when(mapper.selectById(7L)).thenReturn(annotation(7L, "HIGHLIGHT", 1));
        service.delete(10L, 7L);
        verify(mapper).deleteById(7L);
    }

    @Test
    void shouldRejectCrossPaperUpdateAndDelete() {
        when(mapper.selectById(7L)).thenReturn(annotation(7L, "HIGHLIGHT", 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(99L, 7L, request("HIGHLIGHT", 1, "#f44336", null)));
        assertThrows(IllegalArgumentException.class, () -> service.delete(99L, 7L));
        verify(mapper, never()).deleteById(7L);
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
        Map<String, Object> quad = Map.of(
                "x1", 0.1, "y1", 0.2, "x2", 0.3, "y2", 0.2,
                "x3", 0.3, "y3", 0.16, "x4", 0.1, "y4", 0.16);
        if ("NOTE".equals(type)) coords.put("anchorQuads", List.of(quad));
        if ("COMMENT".equals(type)) coords.put("anchorPoint", Map.of("x", 0.1, "y", 0.2));
        if ("HIGHLIGHT".equals(type) || "UNDERLINE".equals(type)) coords.put("quads", List.of(quad));
        r.setCoordinates(coords);
        return r;
    }
}
