package com.research.assistant.service.annotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.dto.AnnotationRequest;
import com.research.assistant.entity.PaperAnnotation;
import com.research.assistant.mapper.PaperAnnotationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 批注服务。
 */
@Service
public class AnnotationService {

    private static final Logger log = LoggerFactory.getLogger(AnnotationService.class);
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "HIGHLIGHT", "UNDERLINE", "NOTE", "COMMENT", "FREEHAND");

    private final PaperAnnotationMapper annotationMapper;
    private final ObjectMapper objectMapper;

    public AnnotationService(PaperAnnotationMapper annotationMapper, ObjectMapper objectMapper) {
        this.annotationMapper = annotationMapper;
        this.objectMapper = objectMapper;
    }

    public List<AnnotationDto> listByPaper(Long paperId) {
        return annotationMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnnotation>()
                                .eq(PaperAnnotation::getPaperId, paperId)
                                .orderByAsc(PaperAnnotation::getPage)
                                .orderByAsc(PaperAnnotation::getCreatedAt))
                .stream()
                .map(this::toDto)
                .toList();
    }

    public AnnotationDto create(Long paperId, AnnotationRequest request) {
        return create(paperId, request, Boolean.FALSE);
    }

    public AnnotationDto create(Long paperId, AnnotationRequest request, boolean aiGenerated) {
        PaperAnnotation entity = new PaperAnnotation();
        entity.setPaperId(paperId);
        entity.setAiGenerated(aiGenerated);
        copyFromRequest(entity, request);
        annotationMapper.insert(entity);
        return toDto(entity);
    }

    public AnnotationDto update(Long paperId, Long annotationId, AnnotationRequest request) {
        PaperAnnotation entity = requireOwned(paperId, annotationId);
        copyFromRequest(entity, request);
        annotationMapper.updateById(entity);
        return toDto(entity);
    }

    public void delete(Long paperId, Long annotationId) {
        requireOwned(paperId, annotationId);
        annotationMapper.deleteById(annotationId);
    }

    private PaperAnnotation requireOwned(Long paperId, Long annotationId) {
        PaperAnnotation entity = annotationMapper.selectById(annotationId);
        if (entity == null || !java.util.Objects.equals(entity.getPaperId(), paperId)) {
            throw new IllegalArgumentException("批注不存在: " + annotationId);
        }
        return entity;
    }

    private void copyFromRequest(PaperAnnotation entity, AnnotationRequest request) {
        validateRequest(request);
        boolean wasCompleted = Boolean.TRUE.equals(entity.getCompleted());
        boolean completed = "COMMENT".equals(request.getType()) && Boolean.TRUE.equals(request.getCompleted());
        entity.setType(request.getType());
        entity.setPage(request.getPage());
        entity.setColor(request.getColor());
        entity.setNote(request.getNote());
        entity.setCompleted(completed);
        entity.setCompletedAt(completed
                ? (wasCompleted && entity.getCompletedAt() != null ? entity.getCompletedAt() : LocalDateTime.now())
                : null);
        entity.setCoordinatesJson(toJson(request.getCoordinates()));
    }

    private void validateRequest(AnnotationRequest request) {
        String type = request.getType();
        Map<String, Object> coordinates = request.getCoordinates();
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的标注类型: " + type);
        }
        if (request.getPage() == null || request.getPage() < 1) {
            throw new IllegalArgumentException("标注页码无效");
        }
        if (("NOTE".equals(type) || "COMMENT".equals(type))
                && (request.getNote() == null || request.getNote().isBlank())) {
            throw new IllegalArgumentException("笔记或批注内容不能为空");
        }
        if ("NOTE".equals(type) && !hasList(coordinates, "anchorQuads")) {
            throw new IllegalArgumentException("笔记必须锚定选区");
        }
        if ("COMMENT".equals(type) && !(coordinates != null && coordinates.get("anchorPoint") instanceof Map)) {
            throw new IllegalArgumentException("批注必须锚定页面内容");
        }
        if (("HIGHLIGHT".equals(type) || "UNDERLINE".equals(type)) && !hasList(coordinates, "quads")) {
            throw new IllegalArgumentException("文字标记缺少选区坐标");
        }
    }

    private boolean hasList(Map<String, Object> coordinates, String key) {
        return coordinates != null
                && coordinates.get(key) instanceof List<?> values
                && !values.isEmpty();
    }

    private AnnotationDto toDto(PaperAnnotation entity) {
        AnnotationDto dto = new AnnotationDto();
        dto.setId(entity.getId());
        dto.setPaperId(entity.getPaperId());
        dto.setType(entity.getType());
        dto.setPage(entity.getPage());
        dto.setColor(entity.getColor());
        dto.setNote(entity.getNote());
        dto.setCoordinates(fromJson(entity.getCoordinatesJson()));
        dto.setAiGenerated(entity.getAiGenerated());
        dto.setCompleted(Boolean.TRUE.equals(entity.getCompleted()));
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private String toJson(Map<String, Object> coordinates) {
        if (coordinates == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(coordinates);
        } catch (JsonProcessingException e) {
            log.warn("坐标 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("坐标 JSON 反序列化失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
