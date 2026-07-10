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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 批注服务。
 */
@Service
public class AnnotationService {

    private static final Logger log = LoggerFactory.getLogger(AnnotationService.class);

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

    public AnnotationDto update(Long annotationId, AnnotationRequest request) {
        PaperAnnotation entity = annotationMapper.selectById(annotationId);
        if (entity == null) {
            throw new IllegalArgumentException("批注不存在: " + annotationId);
        }
        copyFromRequest(entity, request);
        annotationMapper.updateById(entity);
        return toDto(entity);
    }

    public void delete(Long annotationId) {
        annotationMapper.deleteById(annotationId);
    }

    private void copyFromRequest(PaperAnnotation entity, AnnotationRequest request) {
        entity.setType(request.getType());
        entity.setPage(request.getPage());
        entity.setColor(request.getColor());
        entity.setNote(request.getNote());
        entity.setCoordinatesJson(toJson(request.getCoordinates()));
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
